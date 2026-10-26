package com.axnavigator.task;

import com.axnavigator.recovery.CommandQueue;

import java.time.*;
import java.util.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.ax.AxAssessmentRepository;
import com.axnavigator.common.*;
import com.axnavigator.company.DepartmentRepository;
import com.axnavigator.doc.*;
import com.axnavigator.extraction.*;
import com.axnavigator.task.TaskDtos.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TaskService {
    private final CommandQueue commands;
    private final AiTaskRepository tasks;
    private final DepartmentRepository departments;
    private final DocumentRepository documents;
    private final ExtractionDraftRepository drafts;
    private final AxAssessmentRepository assessments;
    private final JsonSupport json;
    private final ApplicationEventPublisher events;
    private final ZoneId zone;

    public TaskService(AiTaskRepository tasks, DepartmentRepository departments, DocumentRepository documents, ExtractionDraftRepository drafts,
            AxAssessmentRepository assessments, JsonSupport json, ApplicationEventPublisher events, AxProperties properties, CommandQueue commands) {
        this.commands = commands;
        this.tasks = tasks; this.departments = departments; this.documents = documents; this.drafts = drafts; this.assessments = assessments;
        this.json = json; this.events = events; this.zone = ZoneId.of(properties.timeZone());
    }

    @Transactional
    public Created create(Long userId, Long companyId, CreateRequest request) {
        // 색인 중인 문서로 Agent를 시작하면 빈 documentIds로 추출 작업이 즉시 실패한다.
        boolean confirmed = departments.existsByCompanyId(companyId);
        List<Long> documentIds = confirmed ? List.of() : documents.findByCompanyIdAndStatusOrderByDocumentId(companyId, DocumentStatus.INDEXED)
            .stream().map(Document::getDocumentId).toList();
        if (!confirmed && documentIds.isEmpty()) {
            throw ApiException.invalidState("분석할 색인 완료 문서가 없습니다. PDF를 업로드하고 문서 색인이 완료된 후 다시 요청해 주세요.");
        }
        AiTask task = new AiTask();
        task.setTaskId(UUID.randomUUID().toString()); task.setUserId(userId); task.setCompanyId(companyId);
        task.setRequestText(request.requestText()); task.setStatus(TaskStatus.QUEUED);
        tasks.saveAndFlush(task);
        // Agent는 DB 커밋 이후 호출한다. 202 접수와 외부 HTTP 실행을 분리한다.
        // 부서가 없으면 아직 기업 데이터를 확정하지 않은 것이다. Agent는 색인 완료 문서에서 먼저 추출한다.
        var command = new TaskSignals.Run(task.getTaskId(), companyId, userId, request.requestText(), confirmed, documentIds);
        commands.enqueue(command);
        events.publishEvent(command);
        return new Created(task.getTaskId());
    }

    @Transactional(readOnly = true)
    public Detail detail(String taskId, Long userId, Long companyId) { return snapshot(owned(find(taskId), userId, companyId)); }

    @Transactional(readOnly = true)
    public ChatContext chatContext(String taskId, Long userId, Long companyId) {
        var task = owned(find(taskId), userId, companyId);
        return new ChatContext(task.getTaskId(), task.getStatus(), json.read(task.getResult()), task.getRequestText());
    }

    @Transactional(readOnly = true)
    public List<History> history(Long userId, Long companyId) {
        return tasks.findByUserIdAndDeletedAtIsNullOrderByCreatedAtDesc(userId).stream().filter(t -> t.getCompanyId().equals(companyId))
            .map(t -> new History(t.getTaskId(), t.getStatus(), t.getRequestText(), t.getCreatedAt(), t.getCompletedAt())).toList();
    }

    /** 이력에서만 숨긴다. 진행 중인 작업은 Agent 콜백이 계속 들어오므로 종료 후에만 삭제할 수 있다. */
    @Transactional
    public void delete(String taskId, Long userId, Long companyId) {
        AiTask task = owned(lock(taskId), userId, companyId);
        if (!terminal(task.getStatus())) throw ApiException.invalidState("진행 중인 작업은 삭제할 수 없습니다. 작업이 완료·실패·거절된 후 다시 시도해 주세요.");
        task.setDeletedAt(LocalDateTime.now(zone));
    }

    @Transactional
    public void approve(String taskId, Long userId, Long companyId, ApprovalRequest request) {
        AiTask task = owned(lock(taskId), userId, companyId);
        if (task.getStatus() != TaskStatus.WAITING_APPROVAL) throw ApiException.invalidState("승인 대기 중인 작업만 승인하거나 거절할 수 있습니다.");
        ObjectNode result = resultObject(json.read(task.getResult()));
        ObjectNode approval = json.object();
        approval.put("decision", request.decision().name()); approval.put("userId", userId);
        if (request.reason() == null) approval.putNull("reason"); else approval.put("reason", request.reason());
        approval.put("decidedAt", OffsetDateTime.now(zone).toString()); result.set("approval", approval);
        task.setResult(json.write(result));
        task.setStatus(request.decision() == Decision.APPROVE ? TaskStatus.RUNNING : TaskStatus.REJECTED);
        if (task.getStatus() == TaskStatus.REJECTED) {
            task.setCompletedAt(LocalDateTime.now(zone)); events.publishEvent(new TaskSignals.Close(taskId));
        }
        var command = new TaskSignals.Resume(taskId, request.decision(), userId, request.reason());
        commands.enqueue(command);
        events.publishEvent(command);
    }

    @Transactional
    public Detail update(String taskId, UpdateRequest request) {
        AiTask task = lock(taskId);
        if (request.result() != null) {
            ObjectNode incoming = resultObject(request.result());
            // 내부 호출자가 approval을 만들어 넣거나 Spring의 승인 기록을 덮어쓸 수 없다.
            incoming.remove("approval");
            JsonNode existing = json.read(task.getResult());
            if (existing != null && existing.has("approval")) incoming.set("approval", existing.get("approval"));
            task.setResult(json.write(incoming));
        }
        if (request.status() != null) transition(task, request.status());
        if (request.errorMessage() != null) task.setErrorMessage(request.errorMessage());
        if (request.traceUrl() != null) task.setTraceUrl(request.traceUrl());
        if (request.budgetKrw() != null) task.setBudgetKrw(request.budgetKrw());
        if (terminal(task.getStatus())) {
            if (task.getStatus() == TaskStatus.REJECTED) events.publishEvent(new TaskSignals.Close(taskId));
            else events.publishEvent(new TaskSignals.Publish(terminalEvent(task)));
        }
        return snapshot(task);
    }

    @Transactional
    public void receive(String taskId, ProgressEvent event) {
        AiTask task = lock(taskId);
        if (!taskId.equals(event.taskId())) throw ApiException.validation("경로와 이벤트의 taskId가 일치해야 합니다.");
        if (!event.data().isObject()) throw ApiException.validation("이벤트 data는 JSON 객체여야 합니다.");
        switch (event.type()) {
            case CONFIRMATION_REQUIRED -> {
                if (drafts.findByTaskId(taskId).filter(d -> d.getStatus() == DraftStatus.DRAFT).isEmpty())
                    throw ApiException.invalidState("추출 결과를 먼저 저장해야 확정을 요청할 수 있습니다.");
                transition(task, TaskStatus.WAITING_CONFIRMATION);
            }
            case APPROVAL_REQUIRED -> transition(task, TaskStatus.WAITING_APPROVAL);
            case DONE -> {
                // DONE 이벤트의 결과도 동일한 승인 보존 규칙으로 저장한다.
                JsonNode result = event.data().get("result");
                if (result != null) update(taskId, new UpdateRequest(null, result, null, null, null));
                transition(task, TaskStatus.DONE);
            }
            case FAILED -> {
                transition(task, TaskStatus.FAILED);
                task.setErrorMessage(event.data().path("message").asString("분석 작업에 실패했습니다."));
                if (event.data().hasNonNull("traceUrl")) {
                    String trace = event.data().get("traceUrl").asString();
                    if (trace.length() > 500) throw ApiException.validation("traceUrl은 500자 이하여야 합니다.");
                    task.setTraceUrl(trace);
                }
            }
            default -> {
                if (terminal(task.getStatus())) throw ApiException.invalidState("종료된 작업에는 진행 이벤트를 보낼 수 없습니다.");
            }
        }
        // DONE은 저장된 승인 기록을 포함한 서버 스냅샷으로 중계한다.
        events.publishEvent(new TaskSignals.Publish(event.type() == EventType.DONE ? terminalEvent(task) : event));
    }

    @Transactional
    public void dispatchFailed(String taskId) {
        AiTask task = lock(taskId);
        if (terminal(task.getStatus())) return;
        task.setErrorMessage("AI Agent 연결에 실패했습니다. 잠시 후 다시 요청해 주세요.");
        transition(task, TaskStatus.FAILED);
        events.publishEvent(new TaskSignals.Publish(terminalEvent(task)));
    }

    public AiTask find(String taskId) { return tasks.findById(taskId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다.")); }
    public AiTask lock(String taskId) { return tasks.lockByTaskId(taskId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다.")); }
    public AiTask owned(AiTask task, Long userId, Long companyId) {
        if (!task.getUserId().equals(userId) || !task.getCompanyId().equals(companyId)) throw ApiException.forbidden("본인이 요청한 작업만 접근할 수 있습니다.");
        if (task.getDeletedAt() != null) throw new ApiException(ErrorCode.NOT_FOUND, "작업을 찾을 수 없습니다.");
        return task;
    }
    /** 사용자 확정 API만 WAITING_CONFIRMATION에서 RUNNING으로 바꾸고 Agent를 재개한다. */
    public void confirmed(AiTask task) {
        if (task.getStatus() != TaskStatus.WAITING_CONFIRMATION) throw ApiException.invalidState("확정 대기 중인 작업만 확정할 수 있습니다.");
        task.setStatus(TaskStatus.RUNNING);
        var command = new TaskSignals.Confirm(task.getTaskId(), task.getUserId());
        commands.enqueue(command);
        events.publishEvent(command);
    }
    public static boolean terminal(TaskStatus status) { return status == TaskStatus.DONE || status == TaskStatus.FAILED || status == TaskStatus.REJECTED; }
    public Detail snapshot(AiTask task) { return new Detail(task.getTaskId(), task.getStatus(), json.read(task.getResult()), task.getTraceUrl()); }
    private ObjectNode resultObject(JsonNode value) {
        if (value == null || value.isNull()) return json.object();
        if (!value.isObject()) throw ApiException.validation("result는 JSON 객체여야 합니다.");
        return ((ObjectNode)value).deepCopy();
    }
    private void transition(AiTask task, TaskStatus next) {
        TaskStatus current = task.getStatus();
        if (current == next) return;
        boolean valid = switch (current) {
            case QUEUED -> next == TaskStatus.RUNNING || next == TaskStatus.FAILED;
            case RUNNING -> next == TaskStatus.WAITING_CONFIRMATION || next == TaskStatus.WAITING_APPROVAL || next == TaskStatus.DONE || next == TaskStatus.FAILED;
            case WAITING_CONFIRMATION, WAITING_APPROVAL -> next == TaskStatus.FAILED;
            default -> false;
        };
        if (!valid) throw ApiException.invalidState("허용되지 않은 작업 상태 변경입니다: " + current + " → " + next);
        // 승인 없이 내부 호출로 완료시키는 것을 막는다. 예외: 추천할 솔루션이 없어 성숙도 진단만 저장하고 끝나는 작업(로드맵 없음).
        JsonNode result = Optional.ofNullable(json.read(task.getResult())).orElse(json.object());
        boolean approved = "APPROVE".equals(result.path("approval").path("decision").asString());
        boolean assessmentOnly = result.path("roadmap").isMissingNode() || result.path("roadmap").isNull();
        if (next == TaskStatus.DONE && !approved && !(assessmentOnly && assessments.existsByTaskId(task.getTaskId())))
            throw ApiException.invalidState("승인된 작업이나 성숙도 진단만 저장한 작업만 완료할 수 있습니다.");
        task.setStatus(next);
        if (terminal(next)) task.setCompletedAt(LocalDateTime.now(zone));
    }
    public ProgressEvent terminalEvent(AiTask task) {
        ObjectNode data = json.object();
        EventType type;
        if (task.getStatus() == TaskStatus.DONE) { type = EventType.DONE; data.set("result", json.read(task.getResult())); }
        else { type = EventType.FAILED; data.put("message", task.getErrorMessage()); if (task.getTraceUrl() == null) data.putNull("traceUrl"); else data.put("traceUrl", task.getTraceUrl()); }
        return new ProgressEvent(type, task.getTaskId(), OffsetDateTime.now(zone), "backend", data);
    }
}
