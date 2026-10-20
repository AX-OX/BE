package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessSystemRepository extends JpaRepository<ProcessSystem, ProcessSystemId> {

	List<ProcessSystem> findByIdSystemIdIn(Collection<Long> systemIds);

}
