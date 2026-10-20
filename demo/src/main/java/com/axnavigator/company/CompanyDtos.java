package com.axnavigator.company;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.axnavigator.ax.calc.DataConfidence;

import tools.jackson.databind.JsonNode;

/**
 * /internal/companies/** · /internal/solutions 응답 (기준 문서 §7, camelCase).
 * 마스킹은 Agent Guardrail 담당이므로 내부 API는 담당자 이메일·연락처 원본을 준다.
 */
public final class CompanyDtos {

	private CompanyDtos() {
	}

	public record DepartmentResponse(Long departmentId, String departmentName, String managerName,
			String managerEmail, String managerPhone) {

		static DepartmentResponse from(Department d) {
			return new DepartmentResponse(d.getDepartmentId(), d.getDepartmentName(), d.getManagerName(),
					d.getManagerEmail(), d.getManagerPhone());
		}

	}

	public record CompanyResponse(Long companyId, String companyName, String industry, Integer employeeCount,
			Long axBudgetKrw, List<DepartmentResponse> departments) {
	}

	public record ProcessResponse(Long processId, Long departmentId, String departmentName, String processName,
			String purpose, JsonNode processSteps, BigDecimal standardizationLevel, boolean requiresApproval,
			String exceptionRules) {
	}

	public record MetricMonth(Long metricId, LocalDate periodStart, LocalDate periodEnd, Integer processingCount,
			BigDecimal avgProcessingMinutes, BigDecimal errorRate, Integer hourlyLaborCostKrw, String dataSource) {
	}

	/**
	 * 업무별 최근 3개 완료 월 지표와 집계값 (집계는 계산 모듈 재사용).
	 * 계약 변경 제안: 반올림 자릿수 monthlyAvgCount·annualHours 소수 2자리, weightedHourlyLaborCostKrw 원 단위.
	 * 계약 변경 제안: 3개월이 다 없으면 집계값은 null이고 missingMonths(["2026-08"])에 빠진 월을 준다.
	 */
	public record ProcessMetricsResponse(Long processId, String processName, Long departmentId,
			List<MetricMonth> months, BigDecimal monthlyAvgCount, BigDecimal annualHours,
			Long weightedHourlyLaborCostKrw, DataConfidence dataConfidence, List<String> missingMonths) {
	}

	public record SystemProcess(Long processId, String usagePurpose) {
	}

	public record SystemResponse(Long systemId, String systemName, SystemType systemType, boolean apiAvailable,
			DataAccessLevel dataAccessLevel, boolean cloudBased, List<SystemProcess> processes) {
	}

	public record BudgetResponse(Long companyId, Long axBudgetKrw) {
	}

	/** 카탈로그 전체. 비율은 0~1 숫자. */
	public record SolutionResponse(Long solutionId, Long targetProcessId, Long dependsOnSolutionId,
			SolutionType solutionType, String solutionName, String description, Long baseBuildCostKrw,
			Long monthlyRunCostKrw, BigDecimal automationRateLow, BigDecimal automationRateBase,
			BigDecimal automationRateHigh, BigDecimal timeReductionRateLow, BigDecimal timeReductionRateBase,
			BigDecimal timeReductionRateHigh, BigDecimal minStandardizationLevel, boolean requiresApi,
			DataSourceType dataSource) {

		static SolutionResponse from(AiSolution s) {
			return new SolutionResponse(s.getSolutionId(), s.getTargetProcessId(), s.getDependsOnSolutionId(),
					s.getSolutionType(), s.getSolutionName(), s.getDescription(), s.getBaseBuildCostKrw(),
					s.getMonthlyRunCostKrw(), s.getAutomationRateLow(), s.getAutomationRateBase(),
					s.getAutomationRateHigh(), s.getTimeReductionRateLow(), s.getTimeReductionRateBase(),
					s.getTimeReductionRateHigh(), s.getMinStandardizationLevel(), s.isRequiresApi(),
					s.getDataSource());
		}

	}

}
