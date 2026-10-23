package com.axnavigator.doc;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface DocumentRepository extends JpaRepository<Document, Long> {

	List<Document> findByCompanyIdOrderByDocumentIdDesc(Long companyId);

	long countByCompanyIdAndStatusNot(Long companyId, DocumentStatus status);

	List<Document> findByCompanyIdAndStatusOrderByDocumentId(Long companyId, DocumentStatus status);

	boolean existsByCompanyIdAndSha256AndStatusNot(Long companyId, String sha256, DocumentStatus status);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from Document d where d.documentId = :documentId")
	Optional<Document> lockById(Long documentId);

}
