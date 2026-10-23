package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

	List<Department> findByCompanyIdOrderByDepartmentId(Long companyId);

	/** 부서가 하나라도 있으면 기업 데이터가 확정된 것으로 본다. */
	boolean existsByCompanyId(Long companyId);

}
