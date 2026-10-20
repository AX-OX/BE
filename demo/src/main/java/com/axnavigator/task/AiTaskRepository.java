package com.axnavigator.task;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface AiTaskRepository extends JpaRepository<AiTask, String> {
    List<AiTask> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from AiTask t where t.taskId = :taskId")
    Optional<AiTask> lockByTaskId(String taskId);
}
