package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessMetricRepository extends JpaRepository<ProcessMetric, Long> {

	List<ProcessMetric> findByProcessIdInOrderByProcessIdAscPeriodStartAsc(Collection<Long> processIds);

}
