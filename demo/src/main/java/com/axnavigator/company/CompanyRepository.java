package com.axnavigator.company;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface CompanyRepository extends JpaRepository<Company, Long> {

	/** 같은 기업의 동시 확정을 막는다. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from Company c where c.companyId = :companyId")
	Optional<Company> lockById(Long companyId);

}
