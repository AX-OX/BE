package com.axnavigator.company;

import java.math.BigDecimal;
import java.time.LocalDate;

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
@Table(name = "process_metrics")
@Getter
@Setter
@NoArgsConstructor
public class ProcessMetric {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "metric_id")
	private Long metricId;

	@Column(name = "process_id", nullable = false)
	private Long processId;

	@Column(name = "period_start", nullable = false)
	private LocalDate periodStart;

	@Column(name = "period_end", nullable = false)
	private LocalDate periodEnd;

	@Column(name = "processing_count", nullable = false)
	private Integer processingCount;

	@Column(name = "avg_processing_minutes", nullable = false, precision = 8, scale = 2)
	private BigDecimal avgProcessingMinutes;

	@Column(name = "error_rate", precision = 5, scale = 4)
	private BigDecimal errorRate;

	@Column(name = "hourly_labor_cost_krw", nullable = false)
	private Integer hourlyLaborCostKrw;

	@Enumerated(EnumType.STRING)
	@Column(name = "data_source", nullable = false, length = 20)
	private DataSourceType dataSource;

}
