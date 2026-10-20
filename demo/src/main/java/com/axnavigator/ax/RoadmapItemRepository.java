package com.axnavigator.ax;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;

public interface RoadmapItemRepository extends JpaRepository<RoadmapItem, Long> {
    List<RoadmapItem> findByRoadmapIdOrderByPriorityOrder(Long roadmapId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from RoadmapItem i where i.itemId = :itemId")
    Optional<RoadmapItem> lockByItemId(Long itemId);
}
