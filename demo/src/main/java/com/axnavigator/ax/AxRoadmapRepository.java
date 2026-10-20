package com.axnavigator.ax;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AxRoadmapRepository extends JpaRepository<AxRoadmap, Long> {

	Optional<AxRoadmap> findByTaskId(String taskId);

}
