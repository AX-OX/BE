package com.axnavigator.doc;

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
@Table(name = "document_pages")
@Getter
@Setter
@NoArgsConstructor
public class DocumentPage {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "page_id")
	private Long pageId;

	@Column(name = "document_id", nullable = false)
	private Long documentId;

	/** 1부터 시작. */
	@Column(name = "page_no", nullable = false)
	private Integer pageNo;

	@Column(name = "content", nullable = false, columnDefinition = "text")
	private String content;

}
