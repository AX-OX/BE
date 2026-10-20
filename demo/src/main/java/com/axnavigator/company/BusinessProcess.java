package com.axnavigator.company;

import java.math.BigDecimal;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "business_processes")
@Getter
@Setter
@NoArgsConstructor
public class BusinessProcess {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "process_id")
	private Long processId;

	@Column(name = "department_id", nullable = false)
	private Long departmentId;

	@Column(name = "process_name", nullable = false, length = 100)
	private String processName;

	@Column(name = "purpose", columnDefinition = "text")
	private String purpose;

	/** JSONB 배열 원문. 파싱은 서비스에서 한다. */
	@JdbcTypeCode(SqlTypes.JSON)
	@Column(name = "process_steps")
	private String processSteps;

	@Column(name = "standardization_level", precision = 3, scale = 2)
	private BigDecimal standardizationLevel;

	@Column(name = "requires_approval", nullable = false)
	private boolean requiresApproval;

	@Column(name = "exception_rules", columnDefinition = "text")
	private String exceptionRules;

}
