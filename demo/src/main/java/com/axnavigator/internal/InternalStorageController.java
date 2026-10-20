package com.axnavigator.internal;

import org.springframework.web.bind.annotation.*;
import com.axnavigator.ax.*;
import com.axnavigator.ax.StorageDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal")
@Tag(name = "분석 결과 저장", description = "business-mcp용 성숙도·로드맵·프로젝트 저장")
@SecurityRequirement(name = "InternalToken")
public class InternalStorageController {
    private final AssessmentService assessments;
    private final RoadmapService roadmaps;
    private final ProjectService projects;
    public InternalStorageController(AssessmentService assessments, RoadmapService roadmaps, ProjectService projects) {
        this.assessments = assessments; this.roadmaps = roadmaps; this.projects = projects;
    }
    @PostMapping("/assessments")
    @Operation(summary = "성숙도 평가 저장", description = "5개 영역의 0~100 점수와 근거를 받아 정책 파일의 가중합을 계산·저장합니다. 누락 영역은 400 VALIDATION_ERROR와 hint.missingAreas로 반환합니다. criteriaVersion은 v1.0이며 사용자 승인은 필요하지 않습니다.")
    public AssessmentResponse assessment(@Valid @RequestBody AssessmentRequest request) { return assessments.save(request); }
    @PostMapping("/roadmaps")
    @Operation(summary = "승인된 로드맵·항목 등록", description = "사용자 승인 전에는 409입니다. 점수·금액은 Spring 기준 시나리오와 재검증하며 불일치는 400 CALCULATION_MISMATCH입니다. 예산·선행 과제·업무 중복을 검증하고 같은 taskId의 재시도는 기존 roadmapId와 itemIds를 반환합니다.")
    public RoadmapResponse roadmap(@Valid @RequestBody RoadmapRequest request) { return roadmaps.save(request); }
    @PostMapping("/projects")
    @Operation(summary = "프로젝트 등록", description = "APPROVED 로드맵의 항목을 REGISTERED 프로젝트로 등록합니다. 같은 itemId는 기존 projectId를 반환합니다. 담당 부서는 로드맵과 같은 기업이어야 합니다.")
    public ProjectResponse project(@Valid @RequestBody ProjectRequest request) { return projects.create(request); }
    @PatchMapping("/projects/{projectId}/status")
    @Operation(summary = "프로젝트 상태 변경", description = "REGISTERED → IN_PROGRESS → COMPLETED를 허용하며 각 상태는 ON_HOLD와 상호 전환할 수 있습니다. 허용되지 않은 변경은 409 INVALID_STATE입니다.")
    public StatusResponse status(@PathVariable Long projectId, @Valid @RequestBody StatusRequest request) { return projects.status(projectId, request); }
}
