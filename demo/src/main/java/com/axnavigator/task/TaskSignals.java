package com.axnavigator.task;

import com.axnavigator.task.TaskDtos.Decision;
import com.axnavigator.task.TaskDtos.ProgressEvent;

public final class TaskSignals {
    private TaskSignals() {}
    /** companyDataConfirmed가 false면 Agent는 documentIds 문서에서 기업 데이터를 먼저 추출한다. */
    public record Run(String taskId, Long companyId, Long userId, String requestText, boolean companyDataConfirmed, java.util.List<Long> documentIds) {}
    public record Confirm(String taskId, Long userId) {}
    public record Resume(String taskId, Decision decision, Long userId, String reason) {}
    public record Publish(ProgressEvent event) {}
    public record Close(String taskId) {}
}
