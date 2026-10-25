package com.axnavigator.ax;

import java.io.IOException;
import java.math.*;
import java.util.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.common.*;
import com.axnavigator.task.*;
import com.axnavigator.ax.StorageDtos.*;
import tools.jackson.databind.JsonNode;

@Service
public class AssessmentService {
    private static final Map<String,String> AREAS = Map.of("data","data_score","infra","infra_score",
        "automation","automation_score","aiCapability","ai_capability_score","governance","governance_score");
    private final AxAssessmentRepository assessments;
    private final TaskService tasks;
    private final JsonSupport json;
    private final JsonNode policy;
    public AssessmentService(AxAssessmentRepository assessments, TaskService tasks, JsonSupport json) throws IOException {
        this.assessments = assessments; this.tasks = tasks; this.json = json;
        try (var in = new ClassPathResource("ax/config/assessment_criteria_v1.json").getInputStream()) {
            policy = json.read(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
    }
    @Transactional
    public AssessmentResponse save(AssessmentRequest request) {
        AiTask task = tasks.lock(request.taskId());
        var existing = assessments.findFirstByTaskIdOrderByAssessmentIdAsc(request.taskId());
        if (existing.isPresent()) return new AssessmentResponse(existing.get().getAssessmentId());
        if (TaskService.terminal(task.getStatus())) throw ApiException.invalidState("종료된 작업의 성숙도는 저장할 수 없습니다.");
        List<String> missing = AREAS.keySet().stream().filter(k -> request.scores().get(k) == null).sorted().toList();
        if (!missing.isEmpty()) throw new ApiException(ErrorCode.VALIDATION_ERROR, "성숙도 5개 영역의 점수가 모두 필요합니다.", Map.of("missingAreas", missing));
        if (!AREAS.keySet().containsAll(request.scores().keySet())) throw ApiException.validation("정의되지 않은 성숙도 영역입니다.");
        if (!policy.path("criteria_version").asString().equals(request.criteriaVersion())) throw ApiException.validation("지원하지 않는 성숙도 기준 버전입니다.");
        if (!request.evidence().isArray()) throw ApiException.validation("성숙도 근거는 배열이어야 합니다.");
        BigDecimal total = BigDecimal.ZERO;
        for (var area : AREAS.entrySet()) {
            BigDecimal score = request.scores().get(area.getKey());
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.valueOf(100)) > 0)
                throw ApiException.validation("성숙도 점수는 0~100이어야 합니다.");
            total = total.add(score.multiply(new BigDecimal(policy.path("weights").path(area.getValue()).asString())));
        }
        AxAssessment a = new AxAssessment();
        a.setTaskId(task.getTaskId()); a.setCompanyId(task.getCompanyId());
        a.setDataScore(request.scores().get("data")); a.setInfraScore(request.scores().get("infra"));
        a.setAutomationScore(request.scores().get("automation")); a.setAiCapabilityScore(request.scores().get("aiCapability"));
        a.setGovernanceScore(request.scores().get("governance")); a.setTotalScore(total.setScale(2, RoundingMode.HALF_UP));
        a.setCriteriaVersion(request.criteriaVersion()); a.setEvidence(json.write(request.evidence()));
        return new AssessmentResponse(assessments.saveAndFlush(a).getAssessmentId());
    }
}
