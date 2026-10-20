package com.axnavigator.company;

import java.io.Serializable;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class ProcessSystemId implements Serializable {

	@Column(name = "process_id", nullable = false)
	private Long processId;

	@Column(name = "system_id", nullable = false)
	private Long systemId;

}
