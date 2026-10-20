package com.axnavigator.ax;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "ax_assessments")
@Getter
@Setter
@NoArgsConstructor
public class AxAssessment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "assessment_id")
	private Long assessmentId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "task_id", nullable = false, length = 36)
	private String taskId;

	@Column(name = "data_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal dataScore;

	@Column(name = "infra_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal infraScore;

	@Column(name = "automation_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal automationScore;

	@Column(name = "ai_capability_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal aiCapabilityScore;

	@Column(name = "governance_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal governanceScore;

	@Column(name = "total_score", nullable = false, precision = 5, scale = 2)
	private BigDecimal totalScore;

	@Column(name = "criteria_version", nullable = false, length = 20)
	private String criteriaVersion;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "evidence")
	private String evidence;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

}
