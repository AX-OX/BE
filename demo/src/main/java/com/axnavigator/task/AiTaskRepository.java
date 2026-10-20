package com.axnavigator.task;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiTaskRepository extends JpaRepository<AiTask, String> {

	List<AiTask> findByUserIdOrderByCreatedAtDesc(Long userId);

}
