package com.axnavigator.extraction;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ExtractionDraftRepository extends JpaRepository<ExtractionDraft, Long> {

	Optional<ExtractionDraft> findByTaskId(String taskId);

}
