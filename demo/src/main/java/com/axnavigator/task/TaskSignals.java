package com.axnavigator.task;

import com.axnavigator.task.TaskDtos.Decision;
import com.axnavigator.task.TaskDtos.ProgressEvent;

public final class TaskSignals {
    private TaskSignals() {}
    public record Run(String taskId, Long companyId, Long userId, String requestText) {}
    public record Resume(String taskId, Decision decision, Long userId, String reason) {}
    public record Publish(ProgressEvent event) {}
    public record Close(String taskId) {}
}
