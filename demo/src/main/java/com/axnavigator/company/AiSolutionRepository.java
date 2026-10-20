package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AiSolutionRepository extends JpaRepository<AiSolution, Long> {

	List<AiSolution> findByTargetProcessIdInOrderBySolutionId(Collection<Long> processIds);

}
