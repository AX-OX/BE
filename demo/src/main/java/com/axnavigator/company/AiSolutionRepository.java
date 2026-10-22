package com.axnavigator.company;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSolutionRepository extends JpaRepository<AiSolution, Long> {

	List<AiSolution> findAllByOrderBySolutionId();

}
