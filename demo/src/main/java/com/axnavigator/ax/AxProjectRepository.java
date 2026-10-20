package com.axnavigator.ax;

import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface AxProjectRepository extends JpaRepository<AxProject, Long> {
    Optional<AxProject> findByItemId(Long itemId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from AxProject p where p.projectId = :projectId")
    Optional<AxProject> lockByProjectId(Long projectId);
}
