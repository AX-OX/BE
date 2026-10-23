package com.axnavigator.agent;

import java.net.URI;
import java.net.http.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.axnavigator.common.*;
import com.axnavigator.doc.DocumentSignals;
import com.axnavigator.task.TaskSignals;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.IdTokenCredentials;
import com.google.auth.oauth2.IdTokenProvider;

@Component
public class AgentClient {
    private final AxProperties properties;
    private final JsonSupport json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public AgentClient(AxProperties properties, JsonSupport json) { this.properties = properties; this.json = json; }
    public boolean enabled() { return properties.agent().enabled(); }
    public void run(TaskSignals.Run command) {
        post(command.taskId(), "run", java.util.Map.of("companyId", command.companyId(), "userId", command.userId(), "requestText", command.requestText(),
            "companyDataConfirmed", command.companyDataConfirmed(), "documentIds", command.documentIds()));
    }
    public void confirm(TaskSignals.Confirm command) {
        post(command.taskId(), "confirm", java.util.Map.of("userId", command.userId()));
    }
    public void resume(TaskSignals.Resume command) {
        var body = new LinkedHashMap<String,Object>();
        body.put("decision", command.decision()); body.put("userId", command.userId()); body.put("reason", command.reason());
        post(command.taskId(), "resume", body);
    }
    /** PDF 원본을 multipart로 AI 색인 API에 보낸다. AI는 결과를 /internal/documents/{id}로 돌려준다. */
    public void ingest(DocumentSignals.Ingest command) {
        String boundary = "ax-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        field(body, boundary, "companyId", command.companyId().toString());
        field(body, boundary, "fileName", command.fileName());
        field(body, boundary, "contentType", command.contentType());
        // 파일명은 fileName 필드로 보내고 part 헤더에는 ASCII 이름만 쓴다.
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"document.pdf\"\r\n"
            + "Content-Type: " + command.contentType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(command.content());
        body.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        send("/documents/" + command.documentId() + "/ingest", "multipart/form-data; boundary=" + boundary,
            HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
    }
    private static void field(ByteArrayOutputStream body, String boundary, String name, String value) {
        body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
            .getBytes(StandardCharsets.UTF_8));
    }
    private void post(String taskId, String operation, Object body) {
        send("/tasks/" + taskId + "/" + operation, "application/json", HttpRequest.BodyPublishers.ofString(json.write(body)));
    }
    private void send(String path, String contentType, HttpRequest.BodyPublisher body) {
        String base = properties.agent().baseUrl();
        if (base == null || base.isBlank()) throw new IllegalStateException("AGENT_BASE_URL is required when AGENT_ENABLED=true");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + path))
            .timeout(Duration.ofSeconds(30)).header("Content-Type", contentType)
            .header("X-Internal-Token", properties.internalToken())
            .POST(body);
        try {
            if (properties.agent().iamAuthEnabled()) {
                request.header("Authorization", "Bearer " + idToken(base));
            }
            HttpResponse<Void> response = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 202) throw new IllegalStateException("Agent returned HTTP " + response.statusCode());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("Agent call interrupted", ex);
        } catch (java.io.IOException ex) { throw new IllegalStateException("Agent connection failed", ex); }
    }
    private String idToken(String base) throws java.io.IOException {
        GoogleCredentials credentials = GoogleCredentials.getApplicationDefault();
        if (!(credentials instanceof IdTokenProvider provider)) {
            throw new IllegalStateException("ADC does not support Google ID tokens");
        }
        URI baseUri = URI.create(base);
        // Cloud Run expects the service URL as audience, without API paths.
        String audience = baseUri.getScheme() + "://" + baseUri.getRawAuthority();
        return IdTokenCredentials.newBuilder().setIdTokenProvider(provider)
            .setTargetAudience(audience).build().refreshAccessToken().getTokenValue();
    }
}
