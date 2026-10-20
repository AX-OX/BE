package com.axnavigator.ax.calc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 계산 입력. DB 엔티티나 CSV에서 만들어 넣는다. */
public final class CalcInputs {

	private CalcInputs() {
	}

	public record ProcessInput(long processId, long departmentId, BigDecimal standardizationLevel,
			boolean requiresApproval) {
	}

	public record MetricInput(long metricId, long processId, LocalDate periodStart, LocalDate periodEnd,
			int processingCount, BigDecimal avgProcessingMinutes, BigDecimal errorRate, int hourlyLaborCostKrw,
			DataConfidence dataSource) {
	}

	public record SystemInput(long systemId, boolean apiAvailable) {
	}

	public record LinkInput(long processId, long systemId) {
	}

	public record SolutionInput(long solutionId, long targetProcessId, Long dependsOnSolutionId,
			long baseBuildCostKrw, long monthlyRunCostKrw, BigDecimal automationRateLow,
			BigDecimal automationRateBase, BigDecimal automationRateHigh, BigDecimal timeReductionRateLow,
			BigDecimal timeReductionRateBase, BigDecimal timeReductionRateHigh, BigDecimal minStandardizationLevel,
			boolean requiresApi) {
	}

	/** 한 기업의 계산 입력 전체. processes·systems는 그 기업 것만, solutions는 그 기업 업무 대상만 넣는다. */
	public record CompanyInput(long companyId, long budgetKrw, List<ProcessInput> processes,
			List<MetricInput> metrics, List<SystemInput> systems, List<LinkInput> links,
			List<SolutionInput> solutions) {
	}

}
