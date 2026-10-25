package com.axnavigator.chat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import com.axnavigator.agent.AgentClient;
import com.axnavigator.common.*;
import static org.assertj.core.api.Assertions.*;

class ChatAgentClientTest {
    private HttpServer server;
    private AgentClient client;
    private String response;
    private String receivedPath;
    private String receivedToken;
    private String receivedBody;
    private int status = 200;

    @BeforeEach void start() throws Exception {
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
    @AfterEach void stop() { server.stop(0); }

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
