package com.axnavigator.company;

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
@Table(name = "departments")
@Getter
@Setter
@NoArgsConstructor
public class Department {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "department_id")
	private Long departmentId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "department_name", nullable = false, length = 50)
	private String departmentName;

	@Column(name = "manager_name", length = 50)
	private String managerName;

	@Column(name = "manager_email", length = 100)
	private String managerEmail;

	@Column(name = "manager_phone", length = 20)
	private String managerPhone;

}
