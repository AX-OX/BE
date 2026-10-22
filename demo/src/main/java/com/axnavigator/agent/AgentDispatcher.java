package com.axnavigator.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import com.axnavigator.task.*;

@Component
public class AgentDispatcher {
    private static final Logger log = LoggerFactory.getLogger(AgentDispatcher.class);
    private final AgentClient agent;
    private final TaskService tasks;
    public AgentDispatcher(AgentClient agent, TaskService tasks) { this.agent = agent; this.tasks = tasks; }

    @Async("agentExecutor")
    @TransactionalEventListener
    public void run(TaskSignals.Run command) {
        if (!agent.enabled()) { log.info("Agent disabled: queued task {}", command.taskId()); return; }
        try {
            tasks.update(command.taskId(), new TaskDtos.UpdateRequest(TaskStatus.RUNNING, null, null, null, null));
            agent.run(command);
        } catch (Exception ex) {
            log.error("Agent run failed for task {}", command.taskId(), ex);
            tasks.dispatchFailed(command.taskId());
        }
    }

    @Async("agentExecutor")
    @TransactionalEventListener
    public void resume(TaskSignals.Resume command) {
        if (!agent.enabled()) { log.info("Agent disabled: resume task {}", command.taskId()); return; }
        try { agent.resume(command); }
        catch (Exception ex) {
            log.error("Agent resume failed for task {}", command.taskId(), ex);
            tasks.dispatchFailed(command.taskId());
        }
    }
}
