package com.axnavigator.ax;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

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
@Table(name = "ax_roadmaps")
@Getter
@Setter
@NoArgsConstructor
public class AxRoadmap {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "roadmap_id")
	private Long roadmapId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "task_id", nullable = false, length = 36)
	private String taskId;

	@Column(name = "assessment_id")
	private Long assessmentId;

	@Column(name = "roadmap_name", nullable = false, length = 200)
	private String roadmapName;

	@Column(name = "budget_limit_krw")
	private Long budgetLimitKrw;

	@Column(name = "total_allocated_krw", nullable = false)
	private Long totalAllocatedKrw;

	@Enumerated(EnumType.STRING)
	@Column(name = "approval_status", nullable = false, length = 20)
	private ApprovalStatus approvalStatus;

	@Column(name = "approved_by")
	private Long approvedBy;

	@Column(name = "approved_at")
	private LocalDateTime approvedAt;

	@Column(name = "rejection_reason", columnDefinition = "text")
	private String rejectionReason;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

}
