package com.axnavigator.internal;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import com.axnavigator.task.*;
import com.axnavigator.task.TaskDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/tasks")
@Tag(name = "작업 콜백", description = "Agent의 작업 상태 저장 및 진행 이벤트 수신")
@SecurityRequirement(name = "InternalToken")
public class InternalTaskController {
    private final TaskService tasks;
    public InternalTaskController(TaskService tasks) { this.tasks = tasks; }
    @PatchMapping("/{taskId}")
    @Operation(summary = "작업 상태·결과 갱신", description = "result는 JSON 객체로 통째로 교체하되 Spring의 approval은 보존합니다. 내부 호출자가 승인 기록을 생성할 수 없습니다. 종료 상태에서 실행 상태로 되돌릴 수 없습니다.")
    public Detail update(@PathVariable String taskId, @Valid @RequestBody UpdateRequest request) { return tasks.update(taskId, request); }
    @PostMapping("/{taskId}/events")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "진행 이벤트 수신", description = "TOKEN, TOOL_START/END, APPROVAL_REQUIRED, DONE, FAILED를 SSE로 중계합니다. 경로와 본문의 taskId는 같아야 합니다. 승인 대기·완료·실패 상태는 DB에도 반영합니다.")
    public void event(@PathVariable String taskId, @Valid @RequestBody ProgressEvent request) { tasks.receive(taskId, request); }
}
