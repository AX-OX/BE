package com.axnavigator.chat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import com.axnavigator.agent.AgentClient;
import com.axnavigator.common.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.assertj.core.api.Assertions.*;

class ChatAgentClientTest {
    private HttpServer server;
    private AgentClient client;
    private String response;
    private String receivedPath;
    private String receivedToken;
    private String receivedBody;
    private int status = 200;
    private final Logger logger = (Logger) LoggerFactory.getLogger(AgentClient.class);
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach void start() throws Exception {
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/messages", exchange -> {
            receivedPath = exchange.getRequestURI().getPath();
            receivedToken = exchange.getRequestHeaders().getFirst("X-Internal-Token");
            receivedBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        var properties = new AxProperties(null, "test-token", new AxProperties.Agent("http://127.0.0.1:" + server.getAddress().getPort(), true, false), null, null, null, "Asia/Seoul");
        client = new AgentClient(properties, new JsonSupport());
    }
    @AfterEach void stop() {
        server.stop(0);
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test void missingUpstreamRouteIsDiagnosableWithoutLeakingUserData() {
        status = 404;
        response = "private-response-with-company-data";
        assertThatThrownBy(() -> client.chat(new ChatDtos.AgentRequest("private-user-message", List.of(), null)))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                assertThat(ex.getMessage()).isEqualTo("답변을 준비하지 못했어요. 잠시 후 다시 시도해 주세요. 새 분석은 시작하지 않았어요.");
            });
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
            .contains("AI_CHAT_FAILED", "reason=UPSTREAM_HTTP", "upstreamStatus=404", "elapsedMs=")
            .doesNotContain("private-response", "private-user-message", "test-token");
        assertThat(logs.list.getFirst().getThrowableProxy()).isNull();
    }

    @Test void malformedJsonLogsOnlyTheFailureCategory() {
        response = "private-invalid-json";
        assertThatThrownBy(() -> client.chat(new ChatDtos.AgentRequest("질문", List.of(), null))).isInstanceOf(ApiException.class);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
            .contains("reason=INVALID_RESPONSE", "upstreamStatus=200").doesNotContain(response, "test-token");
    }

    @Test void connectionFailureIsDifferentFromUpstreamHttpFailure() {
        server.stop(0);
        assertThatThrownBy(() -> client.chat(new ChatDtos.AgentRequest("질문", List.of(), null))).isInstanceOf(ApiException.class);
        assertThat(logs.list).hasSize(1);
        assertThat(logs.list.getFirst().getFormattedMessage())
            .contains("reason=CONNECTION_FAILED", "upstreamStatus=null").doesNotContain("test-token");
    }

    @Test void usesPrivateChatEndpointWithInternalToken() {
        response = "{\"intent\":\"SERVICE_QA\",\"message\":\"설명\",\"analysisRequest\":null}";
        var reply = client.chat(new ChatDtos.AgentRequest("AX란?", List.of(), null));
        assertThat(reply.intent()).isEqualTo(ChatDtos.Intent.SERVICE_QA);
        assertThat(receivedPath).isEqualTo("/chat/messages");
        assertThat(receivedToken).isEqualTo("test-token");
        assertThat(new JsonSupport().read(receivedBody).get("message").asString()).isEqualTo("AX란?");
    }

    @Test void badStatusAndMalformedResponsesAreSafeUnavailableErrors() {
        for (String body : List.of("not json", "null", "[]", "{}", "{\"intent\":\"UNSAFE\",\"message\":\"분석\"}",
                "{\"intent\":\"ANALYZE\",\"message\":\"분석\",\"analysisRequest\":123}")) {
            response = body;
            assertThatThrownBy(() -> client.chat(new ChatDtos.AgentRequest("질문", List.of(), null)))
                .isInstanceOfSatisfying(ApiException.class, ex -> assertThat(ex.code()).isEqualTo(ErrorCode.AI_UNAVAILABLE));
        }
        status = 503;
        response = "private backend details";
        assertThatThrownBy(() -> client.chat(new ChatDtos.AgentRequest("질문", List.of(), null)))
            .isInstanceOfSatisfying(ApiException.class, ex -> {
                assertThat(ex.code()).isEqualTo(ErrorCode.AI_UNAVAILABLE);
                assertThat(ex.getMessage()).doesNotContain("private backend");
            });
    }
}
