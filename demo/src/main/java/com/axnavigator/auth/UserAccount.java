package com.axnavigator.auth;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** users 테이블. 역할 컬럼 없음(MVP). */
@Entity
@Table(name = "users")
@Getter
@Setter
@NoArgsConstructor
public class UserAccount {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "user_id")
	private Long userId;

	@Column(name = "company_id", nullable = false)
	private Long companyId;

	@Column(name = "email", nullable = false, length = 100, unique = true)
	private String email;

	@Column(name = "password_hash", nullable = false, length = 200)
	private String passwordHash;

	@Column(name = "user_name", nullable = false, length = 50)
	private String userName;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

}
