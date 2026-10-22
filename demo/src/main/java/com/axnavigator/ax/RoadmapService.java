package com.axnavigator.ax;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.ax.StorageDtos.*;
import com.axnavigator.ax.calc.CalcResults.*;
import com.axnavigator.common.*;
import com.axnavigator.task.*;

@Service
public class RoadmapService {
    private final TaskService tasks;
    private final AxRoadmapRepository roadmaps;
    private final RoadmapItemRepository items;
    private final AxAssessmentRepository assessments;
    private final AxCalcService calc;
    private final JsonSupport json;
    public RoadmapService(TaskService tasks, AxRoadmapRepository roadmaps, RoadmapItemRepository items,
            AxAssessmentRepository assessments, AxCalcService calc, JsonSupport json) {
        this.tasks = tasks; this.roadmaps = roadmaps; this.items = items; this.assessments = assessments; this.calc = calc; this.json = json;
    }
    @Transactional
    public RoadmapResponse save(RoadmapRequest request) {
        // 동일 task의 승인·콜백·로드맵 등록을 같은 잠금으로 보호한다.
        AiTask task = tasks.lock(request.taskId());
        var approval = Optional.ofNullable(json.read(task.getResult())).orElse(json.object()).path("approval");
        if (!"APPROVE".equals(approval.path("decision").asString())) throw ApiException.invalidState("사용자 승인 후 로드맵을 등록할 수 있습니다.");
        if (!task.getCompanyId().equals(request.companyId())) throw ApiException.validation("작업과 로드맵의 기업이 일치해야 합니다.");
        var existing = roadmaps.findByTaskId(request.taskId());
        if (existing.isPresent()) return response(existing.get());
        if (request.assessmentId() != null) {
            AxAssessment assessment = assessments.findById(request.assessmentId()).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"성숙도 평가를 찾을 수 없습니다."));
            if (!assessment.getTaskId().equals(task.getTaskId()) || !assessment.getCompanyId().equals(task.getCompanyId()))
                throw ApiException.validation("같은 작업·기업의 성숙도 평가만 연결할 수 있습니다.");
        }
        Evaluation evaluation = calc.evaluate(task.getCompanyId(), task.getBudgetKrw());
        Map<CandidateKey,CandidateScore> eligible = evaluation.eligible().stream().collect(Collectors.toMap(CandidateScore::key, Function.identity()));
        Set<Long> processes = new HashSet<>();
        Set<CandidateKey> keys = new HashSet<>();
        Set<Integer> priorities = new HashSet<>();
        long total = 0;
        for (ItemRequest item : request.items()) {
            CandidateKey key = new CandidateKey(item.processId(), item.solutionId());
            CandidateScore score = eligible.get(key);
            if (score == null) throw ApiException.validation("기업의 선정 가능한 업무·솔루션만 등록할 수 있습니다.");
            if (!processes.add(item.processId())) throw ApiException.validation("같은 업무를 중복 등록할 수 없습니다.");
            keys.add(key);
            if (!priorities.add(item.priorityOrder())) throw ApiException.validation("우선순위는 중복될 수 없습니다.");
            if (!item.evidence().isArray()) throw ApiException.validation("과제 근거는 배열이어야 합니다.");
            validateNumbers(item, score);
            total = Math.addExact(total, score.firstYearTcoKrw());
        }
        if (evaluation.budgetKrw() != null && total > evaluation.budgetKrw()) throw ApiException.validation("작업 예산을 초과했습니다.");
        for (ItemRequest item : request.items()) {
            CandidateKey dependency = eligible.get(new CandidateKey(item.processId(), item.solutionId())).dependsOn();
            if (dependency != null && !keys.contains(dependency)) throw ApiException.validation("선행 과제를 같은 로드맵에 포함해야 합니다.");
        }
        AxRoadmap roadmap = new AxRoadmap();
        roadmap.setTaskId(task.getTaskId()); roadmap.setCompanyId(task.getCompanyId()); roadmap.setAssessmentId(request.assessmentId());
        roadmap.setRoadmapName(request.roadmapName()); roadmap.setBudgetLimitKrw(evaluation.budgetKrw()); roadmap.setTotalAllocatedKrw(total);
        roadmap.setApprovalStatus(ApprovalStatus.APPROVED); roadmap.setApprovedBy(task.getUserId());
        roadmap.setApprovedAt(OffsetDateTime.parse(approval.path("decidedAt").asString()).toLocalDateTime());
        roadmaps.saveAndFlush(roadmap);
        for (ItemRequest requestItem : request.items()) {
            RoadmapItem item = new RoadmapItem();
            item.setRoadmapId(roadmap.getRoadmapId()); item.setProcessId(requestItem.processId()); item.setSolutionId(requestItem.solutionId());
            item.setItemName(requestItem.itemName()); item.setPriorityOrder(requestItem.priorityOrder()); item.setPhase(requestItem.phase());
            item.setStartMonth(requestItem.startMonth()); item.setDurationMonths(requestItem.durationMonths()); item.setAllocatedBudgetKrw(requestItem.allocatedBudgetKrw());
            item.setAxScore(requestItem.axScore()); item.setImpactScore(requestItem.impactScore()); item.setFeasibilityScore(requestItem.feasibilityScore());
            item.setExpectedAnnualSavingKrw(requestItem.expectedAnnualSavingKrw()); item.setRoiPct(requestItem.roiPct()); item.setPaybackMonths(requestItem.paybackMonths());
            item.setThreeYearNetValueKrw(requestItem.threeYearNetValueKrw()); item.setRationale(requestItem.rationale()); item.setEvidence(json.write(requestItem.evidence()));
            items.save(item);
        }
        items.flush();
        return response(roadmap);
    }
    private RoadmapResponse response(AxRoadmap roadmap) {
        return new RoadmapResponse(roadmap.getRoadmapId(), items.findByRoadmapIdOrderByPriorityOrder(roadmap.getRoadmapId()).stream().map(RoadmapItem::getItemId).toList());
    }
    private void validateNumbers(ItemRequest item, CandidateScore expected) {
        Scenario base = expected.scenarios().base();
        boolean equal = equal(item.axScore(), expected.axScore()) && equal(item.impactScore(), expected.impactScore())
            && equal(item.feasibilityScore(), expected.feasibilityScore()) && item.allocatedBudgetKrw() == expected.firstYearTcoKrw()
            && item.expectedAnnualSavingKrw() == base.annualSavingKrw() && equal(item.roiPct(), base.roiPct())
            && equal(item.paybackMonths(), base.paybackMonths()) && item.threeYearNetValueKrw() == base.threeYearNetKrw();
        if (!equal) throw new ApiException(ErrorCode.CALCULATION_MISMATCH,"점수 또는 금액이 Spring 계산 결과와 일치하지 않습니다.",Map.of("expected", expected));
    }
    private static boolean equal(BigDecimal a, BigDecimal b) { return a == null ? b == null : b != null && a.compareTo(b) == 0; }
}
