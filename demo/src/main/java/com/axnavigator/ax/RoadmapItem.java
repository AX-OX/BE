package com.axnavigator.ax;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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

@Entity
@Table(name = "roadmap_items")
@Getter
@Setter
@NoArgsConstructor
public class RoadmapItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "item_id")
	private Long itemId;

	@Column(name = "roadmap_id", nullable = false)
	private Long roadmapId;

	@Column(name = "process_id", nullable = false)
	private Long processId;

	@Column(name = "solution_id", nullable = false)
	private Long solutionId;

	@Column(name = "item_name", nullable = false, length = 200)
	private String itemName;

	@Column(name = "priority_order", nullable = false)
	private Integer priorityOrder;

	@Enumerated(EnumType.STRING)
	@Column(name = "phase", nullable = false, length = 10)
	private Phase phase;

	@Column(name = "start_month", nullable = false)
	private Integer startMonth;

	@Column(name = "duration_months", nullable = false)
	private Integer durationMonths;

	/** = 초년도 TCO. */
	@Column(name = "allocated_budget_krw", nullable = false)
	private Long allocatedBudgetKrw;

	@Column(name = "ax_score", nullable = false, precision = 5, scale = 1)
	private BigDecimal axScore;

	@Column(name = "impact_score", nullable = false, precision = 5, scale = 1)
	private BigDecimal impactScore;

	@Column(name = "feasibility_score", nullable = false, precision = 5, scale = 1)
	private BigDecimal feasibilityScore;

	@Column(name = "expected_annual_saving_krw", nullable = false)
	private Long expectedAnnualSavingKrw;

	@Column(name = "roi_pct", nullable = false, precision = 7, scale = 2)
	private BigDecimal roiPct;

	@Column(name = "payback_months", precision = 5, scale = 1)
	private BigDecimal paybackMonths;

	@Column(name = "three_year_net_value_krw", nullable = false)
	private Long threeYearNetValueKrw;

	@Column(name = "rationale", nullable = false, columnDefinition = "text")
	private String rationale;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private String evidence;

}
