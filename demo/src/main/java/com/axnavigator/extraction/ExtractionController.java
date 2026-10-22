package com.axnavigator.extraction;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.axnavigator.auth.CurrentUser;
import com.axnavigator.extraction.ExtractionDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ai/tasks/{taskId}/extraction")
@Tag(name = "기업 데이터 확정", description = "문서에서 추출한 부서, 업무, 지표, 시스템 검토와 확정. 요청자 본인만 접근할 수 있습니다.")
@SecurityRequirement(name = "BearerAuth")
public class ExtractionController {
    private final ExtractionService extractions;
    public ExtractionController(ExtractionService extractions) { this.extractions = extractions; }
    @GetMapping
    @Operation(summary = "추출 결과 조회", description = "Agent가 저장한 추출 결과를 반환합니다. 확정 후에는 확정한 내용을 반환합니다. 다른 사용자 작업은 403, 추출 결과가 없으면 404입니다.")
    public Draft draft(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId) {
        CurrentUser user = CurrentUser.from(jwt); return extractions.draft(taskId, user.userId(), user.companyId());
    }
    @PutMapping("/confirm")
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "추출 결과 확정", description = "WAITING_CONFIRMATION 작업에서 수정한 전체 결과를 보냅니다. key 참조를 검증한 뒤 부서, 업무, 지표, 시스템을 저장하고 Agent를 재개합니다. 지표는 월평균 1건을 계산 기준일의 최근 3개 완료 월에 저장합니다. 사용자가 고친 지표는 dataSource를 USER_INPUT으로 보냅니다. 기업당 한 번만 확정할 수 있습니다(이후 409).")
    public void confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable String taskId, @Valid @RequestBody Payload payload) {
        CurrentUser user = CurrentUser.from(jwt); extractions.confirm(taskId, user.userId(), user.companyId(), payload);
    }
}
