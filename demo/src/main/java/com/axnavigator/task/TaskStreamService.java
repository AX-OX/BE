package com.axnavigator.task;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Service
public class TaskStreamService {
    private final TaskService tasks;
    private final TaskSseHub hub;
    public TaskStreamService(TaskService tasks, TaskSseHub hub) { this.tasks = tasks; this.hub = hub; }
    @Transactional
    public SseEmitter subscribe(String taskId, Long userId, Long companyId) {
        // 종료 처리와 구독 등록 사이 경합을 DB의 동일 작업 잠금으로 직렬화한다.
        AiTask task = tasks.owned(tasks.lock(taskId), userId, companyId);
        SseEmitter emitter = hub.subscribe(taskId);
        if (task.getStatus() == TaskStatus.REJECTED) emitter.complete();
        else if (TaskService.terminal(task.getStatus())) hub.finish(emitter, tasks.terminalEvent(task));
        return emitter;
    }
}
