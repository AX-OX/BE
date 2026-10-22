package com.axnavigator.ax.calc;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;

import com.axnavigator.ax.calc.CalcInputs.MetricInput;

/** 계산 결과. 필드명은 기준 문서 §7 응답(camelCase)과 같다. */
public final class CalcResults {

	private CalcResults() {
	}

	/**
	 * 업무별 공통 기초값 (§6). annualHours·weightedHourlyLaborCost는 반올림 전 값이다.
	 * @param months 기준일 이전 최근 3개 완료 월의 지표 (오래된 월부터)
	 */
	public record BaseValues(long processId, List<MetricInput> months, List<YearMonth> missingMonths,
			BigDecimal annualHours, BigDecimal weightedHourlyLaborCost, BigDecimal monthlyAvgCount,
			DataConfidence dataConfidence) {

		public boolean sufficient() {
			return missingMonths.isEmpty();
		}

	}

	public record Scenario(long annualSavingKrw, BigDecimal roiPct, BigDecimal paybackMonths,
			long threeYearNetKrw) {
	}

	public record Scenarios(Scenario low, Scenario base, Scenario high) {
	}

	/** POST /internal/ax/roi 응답 형식 = 포트폴리오 selected 항목 형식. */
	public record CandidateScore(long processId, long solutionId, Long dependsOnSolutionId, BigDecimal axScore,
			BigDecimal impactScore, BigDecimal feasibilityScore, BigDecimal savedHoursBase, boolean apiOk,
			boolean humanReviewRequired, DataConfidence dataConfidence, List<String> warnings,
			long firstYearTcoKrw, Scenarios scenarios) {
	}

	public record Exclusion(long processId, String reason) {
	}

	/** 최근 3개월 지표가 부족해 선정 가능 후보에서 빠진 후보. */
	public record InsufficientCandidate(long processId, long solutionId, List<YearMonth> missingMonths) {
	}

	/**
	 * 기업 단위 평가 결과.
	 * @param eligible 선정 가능 후보 (solutionId 오름차순)
	 * @param maxSavedHours Impact 정규화 기준 (선정 가능 후보 전체의 saved_hours 최대값)
	 */
	public record Evaluation(long companyId, Long budgetKrw, List<CandidateScore> eligible,
			List<Exclusion> excluded, List<InsufficientCandidate> insufficient, BigDecimal maxSavedHours) {
	}

	public record RunnerUp(List<Long> solutionIds, BigDecimal totalAxScore) {
	}

	/** violations: BUDGET_EXCEEDED, MISSING_DEPENDENCY, DUPLICATE_PROCESS. 복수 위반은 모두, 없으면 빈 배열. */
	public record GreedyTop(List<Long> solutionIds, BigDecimal totalAxScore, long totalFirstYearTcoKrw,
			List<String> violations) {
	}

	/** 지표 부족 후보 경고. code는 INSUFFICIENT_METRICS, missingMonths는 누락된 완료 월(yyyy-MM). */
	public record CandidateWarning(long processId, long solutionId, String code, List<String> missingMonths) {
	}

	/**
	 * warnings는 지표 부족 후보를 알리며 없으면 빈 배열이다. 정책상 제외 업무인 excluded와 구분한다.
	 * 예산 제약이 없으면 budgetKrw·remainingBudgetKrw는 null이다.
	 */
	public record PortfolioResult(boolean insufficient, Long budgetKrw, List<CandidateScore> selected,
			BigDecimal totalAxScore, long totalFirstYearTcoKrw, Long remainingBudgetKrw, int evaluatedCombinations,
			int validCombinations, List<Exclusion> excluded, RunnerUp runnerUp, GreedyTop greedyTop3,
			List<CandidateWarning> warnings) {
	}

}
