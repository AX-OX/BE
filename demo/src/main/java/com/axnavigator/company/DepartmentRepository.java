package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, Long> {

	List<Department> findByCompanyIdOrderByDepartmentId(Long companyId);

}
