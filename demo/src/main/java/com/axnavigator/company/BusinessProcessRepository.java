package com.axnavigator.company;

import java.util.List;
import java.util.Optional;
import java.util.Collection;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BusinessProcessRepository extends JpaRepository<BusinessProcess, Long> {

	List<BusinessProcess> findByDepartmentIdInOrderByProcessId(Collection<Long> departmentIds);

}
