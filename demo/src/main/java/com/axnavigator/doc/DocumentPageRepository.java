package com.axnavigator.doc;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentPageRepository extends JpaRepository<DocumentPage, Long> {

	List<DocumentPage> findByDocumentIdOrderByPageNo(Long documentId);

}
