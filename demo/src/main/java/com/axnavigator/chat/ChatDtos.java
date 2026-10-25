package com.axnavigator.chat;

import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import com.axnavigator.task.TaskStatus;

public final class ChatDtos {
    private ChatDtos() {}
    public enum Intent { GREETING, SERVICE_QA, RESULT_QA, ANALYZE, CLARIFY, OUT_OF_SCOPE }
    public record Message(@NotNull @Pattern(regexp = "user|assistant") String role,
                          @NotBlank @Size(max = 4000) String content) {}
    public record Request(@NotBlank @Size(max = 4000) String message,
                          @Size(max = 12) List<@NotNull @Valid Message> history,
                          @Pattern(regexp = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}") String referenceTaskId) {}
    /** Only server-owned task data crosses the internal boundary. No client result/identity fields. */
    public record TaskContext(String taskId, TaskStatus status, JsonNode result, String requestText) {}
    public record AgentRequest(String message, List<Message> history, TaskContext task) {}
    public record AgentReply(Intent intent, String message, String analysisRequest) {}
    /** taskId is set only after an ANALYZE decision has actually created a task. */
    public record Reply(Intent intent, String message, String taskId) {}
}
