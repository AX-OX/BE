package com.axnavigator.company;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "it_systems")
@Getter
@Setter
@NoArgsConstructor
public class ItSystem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "system_id")
	private Long systemId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "system_name", nullable = false, length = 100)
	private String systemName;

	@Enumerated(EnumType.STRING)
	@Column(name = "system_type", nullable = false, length = 30)
	private SystemType systemType;

	@Column(name = "api_available", nullable = false)
	private boolean apiAvailable;

	@Enumerated(EnumType.STRING)
	@Column(name = "data_access_level", nullable = false, length = 20)
	private DataAccessLevel dataAccessLevel;

	@Column(name = "cloud_based", nullable = false)
	private boolean cloudBased;

}
