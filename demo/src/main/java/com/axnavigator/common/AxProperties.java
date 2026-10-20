package com.axnavigator.common;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * application.yml의 {@code ax.*} 설정. 비밀값(jwt.secret, internal-token)은 환경 변수로만 받는다.
 */
@Validated
@ConfigurationProperties(prefix = "ax")
public record AxProperties(
		@Valid @NotNull Jwt jwt,
		@NotBlank String internalToken,
		@Valid @NotNull Agent agent,
		@Valid @NotNull Cors cors,
		@Valid @NotNull Docs docs,
		@Valid @NotNull Evaluation evaluation,
		@NotNull Long defaultCompanyId,
		@NotBlank String timeZone) {

	public record Jwt(@NotBlank String secret, @NotNull Duration ttl) {

		@AssertTrue(message = "JWT 만료 시간은 0보다 커야 합니다.")
		public boolean isTtlPositive() {
			return ttl != null && !ttl.isNegative() && !ttl.isZero();
		}

		/** HS256 키는 32바이트 이상이어야 한다. */
		@AssertTrue(message = "ax.jwt.secret(JWT_SECRET)은 32바이트 이상이어야 한다")
		public boolean isSecretLongEnough() {
			return secret != null && secret.getBytes(StandardCharsets.UTF_8).length >= 32;
		}

	}

	public record Agent(String baseUrl, boolean enabled) {
	}

	public record Cors(@NotBlank String origin) {
	}

	public record Docs(@NotBlank String dir) {
	}

	public record Evaluation(@NotNull LocalDate asOf) {
	}

}
