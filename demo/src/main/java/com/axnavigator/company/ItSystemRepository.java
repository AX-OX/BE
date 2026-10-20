package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ItSystemRepository extends JpaRepository<ItSystem, Long> {

	List<ItSystem> findByCompanyIdOrderBySystemId(Long companyId);

}
