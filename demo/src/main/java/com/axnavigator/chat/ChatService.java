package com.axnavigator.chat;

import java.util.List;
import org.springframework.stereotype.Service;
import com.axnavigator.agent.AgentClient;
import com.axnavigator.common.ApiException;
import com.axnavigator.common.ErrorCode;
import com.axnavigator.task.TaskDtos;
import com.axnavigator.task.TaskService;
import com.axnavigator.chat.ChatDtos.*;

@Service
public class ChatService {
    private final AgentClient agent;
    private final TaskService tasks;

    public ChatService(AgentClient agent, TaskService tasks) {
        this.agent = agent;
        this.tasks = tasks;
    }

    // Deliberately no transaction around the external model call. TaskService.create
    // keeps the existing short task + durable command transaction when needed.
    public Reply send(Long userId, Long companyId, Request request) {
        List<Message> history = request.history() == null ? List.of() : List.copyOf(request.history());
        if (history.stream().mapToInt(m -> m.content().length()).sum() > 16000) {
            throw ApiException.validation("최근 대화는 16000자 이내로 보내 주세요.");
        }
        TaskContext context = null;
        if (request.referenceTaskId() != null) {
            // Reuses both requester and tenant checks; never accept a client-supplied result.
            var detail = tasks.chatContext(request.referenceTaskId(), userId, companyId);
            context = new TaskContext(detail.taskId(), detail.status(), detail.result(), detail.requestText());
        }
        AgentReply decision = agent.chat(new AgentRequest(request.message().trim(), history, context));
        validate(decision);
        if (decision.intent() != Intent.ANALYZE) {
            return new Reply(decision.intent(), decision.message(), null);
        }
        try {
            var task = tasks.create(userId, companyId, new TaskDtos.CreateRequest(decision.analysisRequest().trim()));
            return new Reply(Intent.ANALYZE, decision.message(), task.taskId());
        } catch (ApiException exception) {
            if (exception.code() != ErrorCode.INVALID_STATE) throw exception;
            // Data readiness is a normal clarification, not a failed analysis card.
            return new Reply(Intent.CLARIFY, exception.getMessage(), null);
        }
    }

    private static void validate(AgentReply reply) {
        if (reply == null || reply.intent() == null || reply.message() == null || reply.message().isBlank()
                || reply.message().length() > 6000
                || (reply.intent() == Intent.ANALYZE && (reply.analysisRequest() == null
                    || reply.analysisRequest().isBlank() || reply.analysisRequest().length() > 4000))
                || (reply.intent() != Intent.ANALYZE && reply.analysisRequest() != null)) {
            throw new ApiException(ErrorCode.AI_UNAVAILABLE, "AI 답변 형식을 확인하지 못했어요. 새 분석은 시작하지 않았어요.");
        }
    }
}
