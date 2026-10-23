package com.axnavigator.extraction;

import java.time.LocalDateTime;

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
@Table(name = "extraction_drafts")
@Getter
@Setter
@NoArgsConstructor
public class ExtractionDraft {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "draft_id")
	private Long draftId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "task_id", nullable = false, length = 36)
	private String taskId;

	/** JSONB 배열 원문. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "document_ids", nullable = false)
	private String documentIds;

	/** JSONB 원문. 확정 후에는 사용자가 확정한 내용으로 바뀐다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "payload", nullable = false)
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private DraftStatus status;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

}
