package com.axnavigator.task;

import java.io.IOException;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.axnavigator.common.GlobalExceptionHandler;
import com.axnavigator.common.JsonSupport;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class TaskSseHubTest {
    private final TaskSseHub hub = new TaskSseHub(new JsonSupport());
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new StreamController(hub))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void streamClosesBeforeCloudRunDeadlineAndCanReconnect() throws Exception {
        MvcResult stream = mvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn();
        MockAsyncContext context = (MockAsyncContext) stream.getRequest().getAsyncContext();
        assertThat(context.getTimeout()).isPositive().isLessThan(60_000L);

        for (AsyncListener listener : context.getListeners()) listener.onTimeout(new AsyncEvent(context));
        mvc.perform(asyncDispatch(stream)).andExpect(status().isOk())
            .andExpect(result -> assertThat(result.getResolvedException()).isNull());
        String completed = stream.getResponse().getContentAsString();
        hub.heartbeat();
        assertThat(stream.getResponse().getContentAsString()).isEqualTo(completed);

        MvcResult reconnected = mvc.perform(get("/stream")).andExpect(request().asyncStarted()).andReturn();
        String initial = reconnected.getResponse().getContentAsString();
        hub.heartbeat();
        assertThat(reconnected.getResponse().getContentAsString()).isEqualTo(initial + initial);
        hub.closeTask("task");
        mvc.perform(asyncDispatch(reconnected)).andExpect(status().isOk());
    }

    @Test
    void disconnectedStreamDoesNotAttemptToWriteJsonErrorBody() throws Exception {
        mvc.perform(get("/disconnected"))
            .andExpect(status().isOk())
            .andExpect(content().string(":ping\n\n"))
            .andExpect(result -> assertThat(result.getResolvedException()).isInstanceOf(AsyncRequestNotUsableException.class));
    }

    @RestController
    static class StreamController {
        private final TaskSseHub hub;
        StreamController(TaskSseHub hub) { this.hub = hub; }

        @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter stream() { return hub.subscribe("task"); }

        @GetMapping(value = "/disconnected", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        void disconnected(HttpServletResponse response) throws IOException {
            response.setContentType(MediaType.TEXT_EVENT_STREAM_VALUE);
            response.getWriter().write(":ping\n\n");
            response.flushBuffer();
            throw new AsyncRequestNotUsableException("Disconnected client", new IOException("Broken pipe"));
        }
    }
}
