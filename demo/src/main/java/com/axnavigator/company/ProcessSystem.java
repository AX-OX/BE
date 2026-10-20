package com.axnavigator.company;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "process_systems")
@Getter
@Setter
@NoArgsConstructor
public class ProcessSystem {

	@EmbeddedId
	private ProcessSystemId id;

	@Column(name = "usage_purpose", length = 200)
	private String usagePurpose;

}
