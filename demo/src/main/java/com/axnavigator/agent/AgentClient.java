package com.axnavigator.agent;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.springframework.stereotype.Component;
import com.axnavigator.common.*;
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
        post(command.taskId(), "run", java.util.Map.of("companyId", command.companyId(), "userId", command.userId(), "requestText", command.requestText()));
    }
    public void resume(TaskSignals.Resume command) {
        var body = new LinkedHashMap<String,Object>();
        body.put("decision", command.decision()); body.put("userId", command.userId()); body.put("reason", command.reason());
        post(command.taskId(), "resume", body);
    }
    private void post(String taskId, String operation, Object body) {
        String base = properties.agent().baseUrl();
        if (base == null || base.isBlank()) throw new IllegalStateException("AGENT_BASE_URL is required when AGENT_ENABLED=true");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base.replaceAll("/+$", "") + "/tasks/" + taskId + "/" + operation))
            .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
            .header("X-Internal-Token", properties.internalToken())
            .POST(HttpRequest.BodyPublishers.ofString(json.write(body)));
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
