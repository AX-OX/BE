package com.axnavigator.ax;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface RoadmapItemRepository extends JpaRepository<RoadmapItem, Long> {

	List<RoadmapItem> findByRoadmapIdOrderByPriorityOrder(Long roadmapId);

}
