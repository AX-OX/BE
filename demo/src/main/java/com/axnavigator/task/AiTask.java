package com.axnavigator.task;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** ai_tasks. task_id = UUID 문자열 = LangGraph thread_id. */
@Entity
@Table(name = "ai_tasks")
@Getter
@Setter
@NoArgsConstructor
public class AiTask {

	@Id
	@Column(name = "task_id", length = 36)
	private String taskId;

	@Column(name = "user_id", nullable = false)
	private Long userId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "request_text", nullable = false, columnDefinition = "text")
	private String requestText;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 30)
	private TaskStatus status;

	/** JSONB 원문. Spring은 approval 키만 관리하고 나머지는 해석하지 않는다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "result")
	private String result;

	@Column(name = "error_message", columnDefinition = "text")
	private String errorMessage;

	@Column(name = "trace_url", length = 500)
	private String traceUrl;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private LocalDateTime updatedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

}
