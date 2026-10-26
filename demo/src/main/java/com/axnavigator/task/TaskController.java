package com.axnavigator.task;

import java.util.List;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import com.axnavigator.auth.CurrentUser;
import com.axnavigator.common.ApiException;
import com.axnavigator.task.TaskDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai/tasks")
@Tag(name = "분석 작업", description = "작업 접수·진행·승인. 요청자 본인만 접근할 수 있습니다.")
@SecurityRequirement(name = "BearerAuth")
public class TaskController {
    private final TaskService tasks;
    private final TaskStreamService streams;
    public TaskController(TaskService tasks, TaskStreamService streams) { this.tasks = tasks; this.streams = streams; }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "분석 작업 접수", description = "JWT의 사용자·기업으로 작업을 생성하고 즉시 202와 taskId를 반환합니다. Agent 실행은 저장 완료 후 비동기로 요청합니다.")
    public Created create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody CreateRequest request) {
        CurrentUser user = CurrentUser.from(jwt); return tasks.create(user.userId(), user.companyId(), request);
    }
    @GetMapping("/{taskId}")
    @Operation(summary = "작업 상태·결과 조회", description = "본인 작업의 상태, JSON 결과, traceUrl을 반환합니다. 다른 사용자 작업은 403, 없는 작업은 404입니다.")
    public Detail detail(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        CurrentUser user = CurrentUser.from(jwt); return tasks.detail(taskId, user.userId(), user.companyId());
    }
    @GetMapping
    @Operation(summary = "내 작업 이력 조회", description = "mine=true로 요청합니다. 본인 작업을 생성일 최신순으로 반환합니다.")
    public List<History> history(@AuthenticationPrincipal Jwt jwt, @RequestParam(defaultValue = "true") boolean mine) {
        if (!mine) throw ApiException.validation("본인의 작업 이력만 조회할 수 있습니다. mine=true로 요청해 주세요.");
        CurrentUser user = CurrentUser.from(jwt); return tasks.history(user.userId(), user.companyId());
    }
    @DeleteMapping("/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "작업 이력 삭제", description = "본인 작업을 이력에서 삭제합니다. 이후 조회·SSE·대화 참조는 404입니다. DONE·FAILED·REJECTED 상태만 삭제할 수 있고 진행 중이면 409입니다. 이미 생성된 진단·로드맵은 유지됩니다.")
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        CurrentUser user = CurrentUser.from(jwt); tasks.delete(taskId, user.userId(), user.companyId());
    }
    @GetMapping(value = "/{taskId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "작업 진행 SSE 구독", description = "fetch 스트리밍에 Authorization 헤더를 전달하세요. 15초마다 : ping을 보내며 DONE·FAILED·거절 또는 45초 경과 시 정상 종료합니다. 진행 중이면 재구독하세요. 이벤트 재생은 제공하지 않으므로 재연결 직후 작업 조회로 상태를 복구하세요.")
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        CurrentUser user = CurrentUser.from(jwt);
        return ResponseEntity.ok().header("X-Accel-Buffering", "no").cacheControl(CacheControl.noCache())
            .body(streams.subscribe(taskId, user.userId(), user.companyId()));
    }
    @PostMapping("/{taskId}/approval")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "작업 승인·거절", description = "WAITING_APPROVAL 상태에서 요청자 본인만 APPROVE 또는 REJECT할 수 있습니다. 승인 기록은 Spring이 생성하고 Agent를 재개합니다. 상태가 다르면 409입니다.")
    public void approve(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId, @Valid @RequestBody ApprovalRequest request) {
        CurrentUser user = CurrentUser.from(jwt); tasks.approve(taskId, user.userId(), user.companyId(), request);
    }
}
