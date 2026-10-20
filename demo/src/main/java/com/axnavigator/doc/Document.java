package com.axnavigator.doc;

import java.time.LocalDateTime;

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
@Table(name = "documents")
@Getter
@Setter
@NoArgsConstructor
public class Document {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "document_id")
	private Long documentId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	/** 공통 문서면 null. */
	@Column(name = "process_id")
	private Long processId;

	@Column(name = "title", nullable = false, length = 200)
	private String title;

	@Enumerated(EnumType.STRING)
	@Column(name = "doc_type", nullable = false, length = 30)
	private DocType docType;

	@Column(name = "file_uri", nullable = false, length = 500)
	private String fileUri;

	@Enumerated(EnumType.STRING)
	@Column(name = "access_level", nullable = false, length = 20)
	private AccessLevel accessLevel;

	@Column(name = "version", nullable = false, length = 20)
	private String version;

	@Column(name = "created_at", nullable = false, insertable = false, updatable = false)
	private LocalDateTime createdAt;

}
