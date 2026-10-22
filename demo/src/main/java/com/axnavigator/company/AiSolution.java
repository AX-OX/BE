package com.axnavigator.company;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** ai_solution_catalog. 업무 유형별 솔루션 템플릿 (ROI 가정값, LLM이 만들지 않음). */
@Entity
@Table(name = "ai_solution_catalog")
@Getter
@Setter
@NoArgsConstructor
public class AiSolution {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "solution_id")
	private Long solutionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "work_type", nullable = false, length = 30)
	private WorkType workType;

	@Column(name = "depends_on_solution_id")
	private Long dependsOnSolutionId;

	@Enumerated(EnumType.STRING)
	@Column(name = "solution_type", nullable = false, length = 30)
	private SolutionType solutionType;

	@Column(name = "solution_name", nullable = false, length = 100)
	private String solutionName;

	@Column(name = "description", columnDefinition = "text")
	private String description;

	@Column(name = "base_build_cost_krw", nullable = false)
	private Long baseBuildCostKrw;

	@Column(name = "monthly_run_cost_krw", nullable = false)
	private Long monthlyRunCostKrw;

	@Column(name = "automation_rate_low", nullable = false, precision = 3, scale = 2)
	private BigDecimal automationRateLow;

	@Column(name = "automation_rate_base", nullable = false, precision = 3, scale = 2)
	private BigDecimal automationRateBase;

	@Column(name = "automation_rate_high", nullable = false, precision = 3, scale = 2)
	private BigDecimal automationRateHigh;

	@Column(name = "time_reduction_rate_low", nullable = false, precision = 3, scale = 2)
	private BigDecimal timeReductionRateLow;

	@Column(name = "time_reduction_rate_base", nullable = false, precision = 3, scale = 2)
	private BigDecimal timeReductionRateBase;

	@Column(name = "time_reduction_rate_high", nullable = false, precision = 3, scale = 2)
	private BigDecimal timeReductionRateHigh;

	@Column(name = "min_standardization_level", precision = 3, scale = 2)
	private BigDecimal minStandardizationLevel;

	@Column(name = "requires_api", nullable = false)
	private boolean requiresApi;

	@Enumerated(EnumType.STRING)
	@Column(name = "data_source", nullable = false, length = 20)
	private DataSourceType dataSource;

}
