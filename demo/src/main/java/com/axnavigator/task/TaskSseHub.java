package com.axnavigator.task;

import java.io.IOException;
import java.util.*;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.axnavigator.common.JsonSupport;
import com.axnavigator.task.TaskDtos.*;
import jakarta.annotation.PreDestroy;

/** 단일 backend 인스턴스용 실시간 중계. 이력 재생 대신 재연결 시 작업 조회로 상태를 복구한다. */
@Component
public class TaskSseHub {
    private final Map<String, Set<SseEmitter>> streams = new HashMap<>();
    private final JsonSupport json;
    public TaskSseHub(JsonSupport json) { this.json = json; }

    public synchronized SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(0L);
        streams.computeIfAbsent(taskId, key -> new HashSet<>()).add(emitter);
        emitter.onCompletion(() -> remove(taskId, emitter));
        emitter.onTimeout(() -> { remove(taskId, emitter); emitter.complete(); });
        emitter.onError(error -> remove(taskId, emitter));
        send(taskId, emitter, SseEmitter.event().comment("ping"));
        return emitter;
    }

    public synchronized void finish(SseEmitter emitter, ProgressEvent event) {
        send(event.taskId(), emitter, SseEmitter.event().data(json.write(event), MediaType.APPLICATION_JSON));
        remove(event.taskId(), emitter); emitter.complete();
    }

    @TransactionalEventListener
    public synchronized void publish(TaskSignals.Publish signal) {
        ProgressEvent event = signal.event();
        for (SseEmitter emitter : List.copyOf(streams.getOrDefault(event.taskId(), Set.of()))) {
            send(event.taskId(), emitter, SseEmitter.event().data(json.write(event), MediaType.APPLICATION_JSON));
        }
        if (event.type() == EventType.DONE || event.type() == EventType.FAILED) closeTask(event.taskId());
    }

    @TransactionalEventListener
    public void close(TaskSignals.Close signal) { closeTask(signal.taskId()); }

    @Scheduled(fixedRate = 15000)
    public synchronized void heartbeat() {
        for (String taskId : List.copyOf(streams.keySet()))
            for (SseEmitter emitter : List.copyOf(streams.getOrDefault(taskId, Set.of())))
                send(taskId, emitter, SseEmitter.event().comment("ping"));
    }
    public synchronized void closeTask(String taskId) {
        Set<SseEmitter> removed = streams.remove(taskId);
        if (removed != null) removed.forEach(SseEmitter::complete);
    }
    private synchronized void remove(String taskId, SseEmitter emitter) {
        Set<SseEmitter> group = streams.get(taskId);
        if (group != null) { group.remove(emitter); if (group.isEmpty()) streams.remove(taskId); }
    }
    private void send(String taskId, SseEmitter emitter, SseEmitter.SseEventBuilder data) {
        try { emitter.send(data); }
        catch (IOException | IllegalStateException ex) { remove(taskId, emitter); }
    }
    @PreDestroy
    public synchronized void shutdown() { List.copyOf(streams.keySet()).forEach(this::closeTask); }
}
