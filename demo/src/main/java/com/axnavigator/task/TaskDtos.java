package com.axnavigator.task;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public final class TaskDtos {
    private TaskDtos() {}
    public record CreateRequest(@NotBlank @Schema(example = "예산 3억 원 내에서 AX 과제 3개를 추천해 주세요.") String requestText) {}
    public record Created(String taskId) {}
    public record Detail(String taskId, TaskStatus status, JsonNode result, String traceUrl) {}
    public record History(String taskId, TaskStatus status, String requestText, LocalDateTime createdAt, LocalDateTime completedAt) {}
    public enum Decision { APPROVE, REJECT }
    public record ApprovalRequest(@NotNull Decision decision, String reason) {}
    public record UpdateRequest(TaskStatus status, JsonNode result, String errorMessage, @Size(max = 500) String traceUrl,
        @Positive @Schema(description = "요청에서 추출한 예산(원). 생략하면 기존 값 유지", example = "300000000") Long budgetKrw) {}
    public enum EventType { TOKEN, TOOL_START, TOOL_END, APPROVAL_REQUIRED, DONE, FAILED }
    public record ProgressEvent(@NotNull EventType type, @NotBlank String taskId,
        @NotNull OffsetDateTime ts, @NotBlank String node, @NotNull JsonNode data) {}
}
