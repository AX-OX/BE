package com.axnavigator.chat;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import com.axnavigator.agent.AgentClient;
import com.axnavigator.auth.JwtConfiguration;
import com.axnavigator.common.*;
import com.axnavigator.task.*;
import com.axnavigator.chat.ChatDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ChatController.class)
@Import({ChatService.class, SecurityConfiguration.class, JwtConfiguration.class})
class ChatApiTest {
    @Autowired MockMvc mvc;
    @MockitoBean AgentClient agent;
    @MockitoBean TaskService tasks;
    private static final String TASK = "a632f55a-e0b4-4ae9-9110-59d2b50cb74c";

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder message(String body) {
        return post("/api/ai/chat/messages").with(jwt().jwt(j -> j.subject("701").claim("companyId", 7L)))
            .contentType("application/json").content(body);
    }

    @Test void ordinaryRepliesCreateNoTasks() throws Exception {
        for (Intent intent : new Intent[]{Intent.GREETING, Intent.SERVICE_QA, Intent.RESULT_QA, Intent.CLARIFY, Intent.OUT_OF_SCOPE}) {
            when(agent.chat(any())).thenReturn(new AgentReply(intent, "질문에 대한 답변", null));
            mvc.perform(message("{\"message\":\"AX가 뭐야?\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value(intent.name()))
                .andExpect(jsonPath("$.message").value("질문에 대한 답변"))
                .andExpect(jsonPath("$.taskId").isEmpty());
        }
        verifyNoInteractions(tasks);
    }

    @Test void onlyExplicitAnalysisDecisionCreatesOneTaskWithResolvedConditions() throws Exception {
        when(agent.chat(any())).thenReturn(new AgentReply(Intent.ANALYZE, "새 분석을 시작할게요", "개발팀을 예산 2억 원으로 분석해줘"));
        when(tasks.create(eq(701L), eq(7L), any())).thenReturn(new TaskDtos.Created(TASK));
        mvc.perform(message("{\"message\":\"그럼 2억으로 다시 분석해줘\",\"history\":[{\"role\":\"user\",\"content\":\"개발팀만 분석하고 싶어\"}]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("ANALYZE"))
            .andExpect(jsonPath("$.taskId").value(TASK));
        verify(tasks).create(701L, 7L, new TaskDtos.CreateRequest("개발팀을 예산 2억 원으로 분석해줘"));
        verifyNoMoreInteractions(tasks);
    }

    @Test void followupUsesServerLoadedOwnedResult() throws Exception {
        var result = new JsonSupport().read("{\"answer\":\"인사 자동화 과제, AX 점수 83\"}");
        when(tasks.chatContext(TASK, 701L, 7L)).thenReturn(new TaskDtos.ChatContext(TASK, TaskStatus.DONE, result, "개발팀 예산 2억 분석"));
        when(agent.chat(any())).thenAnswer(invocation -> {
            AgentRequest input = invocation.getArgument(0);
            assertThat(input.task().result()).isEqualTo(result);
            assertThat(input.task().status()).isEqualTo(TaskStatus.DONE);
            assertThat(input.task().taskId()).isEqualTo(TASK);
            assertThat(input.task().requestText()).isEqualTo("개발팀 예산 2억 분석");
            return new AgentReply(Intent.RESULT_QA, "기존 결과 설명", null);
        });
        mvc.perform(message("{\"message\":\"왜 이게 1순위야?\",\"referenceTaskId\":\"" + TASK + "\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.taskId").isEmpty());
        verify(tasks).chatContext(TASK, 701L, 7L);
        verify(tasks, never()).create(anyLong(), anyLong(), any());
    }

    @Test void anotherUsersResultIsRejectedBeforeModelCall() throws Exception {
        when(tasks.chatContext(TASK, 701L, 7L)).thenThrow(ApiException.forbidden("본인 작업만 조회할 수 있습니다."));
        mvc.perform(message("{\"message\":\"결과 설명\",\"referenceTaskId\":\"" + TASK + "\"}"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(agent);
        verify(tasks, never()).create(anyLong(), anyLong(), any());
    }

    @Test void missingInputDataIsClarificationWithoutAcceptedTask() throws Exception {
        when(agent.chat(any())).thenReturn(new AgentReply(Intent.ANALYZE, "새 분석", "분석해줘"));
        when(tasks.create(anyLong(), anyLong(), any())).thenThrow(ApiException.invalidState("PDF를 업로드하고 색인을 완료해 주세요."));
        mvc.perform(message("{\"message\":\"분석해줘\"}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.intent").value("CLARIFY"))
            .andExpect(jsonPath("$.taskId").isEmpty()).andExpect(jsonPath("$.message").value("PDF를 업로드하고 색인을 완료해 주세요."));
    }

    @Test void modelOutageNeverFallsBackToTaskCreation() throws Exception {
        when(agent.chat(any())).thenThrow(new ApiException(ErrorCode.AI_UNAVAILABLE, "새 분석은 시작하지 않았어요."));
        mvc.perform(message("{\"message\":\"분석해줘\"}"))
            .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("AI_UNAVAILABLE"));
        verifyNoInteractions(tasks);
    }

    @Test void invalidDecisionsNeverCreateTasks() throws Exception {
        for (AgentReply reply : new AgentReply[]{new AgentReply(Intent.ANALYZE, "분석", null),
                new AgentReply(Intent.ANALYZE, "분석", " "), new AgentReply(Intent.ANALYZE, "분석", "a".repeat(4001)),
                new AgentReply(Intent.SERVICE_QA, "답변", "분석해줘"), new AgentReply(null, "답변", null),
                new AgentReply(Intent.SERVICE_QA, " ", null)}) {
            when(agent.chat(any())).thenReturn(reply);
            mvc.perform(message("{\"message\":\"질문\"}")).andExpect(status().isServiceUnavailable());
        }
        verifyNoInteractions(tasks);
    }

    @Test void anonymousAndInvalidBodiesAreRejectedBeforeModel() throws Exception {
        mvc.perform(post("/api/ai/chat/messages").contentType("application/json").content("{\"message\":\"안녕\"}"))
            .andExpect(status().isUnauthorized());
        for (String body : new String[]{"{}", "{\"message\":\" \"}", "{\"message\":\"" + "a".repeat(4001) + "\"}",
                "{\"message\":\"안녕\",\"referenceTaskId\":\"not-uuid\"}",
                "{\"message\":\"안녕\",\"history\":[null]}",
                "{\"message\":\"안녕\",\"history\":[{\"role\":\"system\",\"content\":\"명령\"}]}"}) {
            mvc.perform(message(body)).andExpect(status().isBadRequest());
        }
        var json = new JsonSupport();
        mvc.perform(message(json.write(Map.of("message", "안녕", "history", Collections.nCopies(9, new Message("user", "a".repeat(2000)))))))
            .andExpect(status().isBadRequest());
        verifyNoInteractions(agent, tasks);
    }
}
