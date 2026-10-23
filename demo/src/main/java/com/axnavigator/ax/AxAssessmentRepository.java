package com.axnavigator.ax;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AxAssessmentRepository extends JpaRepository<AxAssessment, Long> {

	boolean existsByTaskId(String taskId);


}
