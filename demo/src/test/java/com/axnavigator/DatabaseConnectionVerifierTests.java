package com.axnavigator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import java.sql.SQLException;
import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(OutputCaptureExtension.class)
class DatabaseConnectionVerifierTests {

	@Test
	void verifiesARealQueryWithoutRequiringDocker(CapturedOutput output) throws Exception {
		try (var dataSource = new HikariDataSource()) {
			dataSource.setJdbcUrl("jdbc:h2:mem:connection-verifier");
			dataSource.setUsername("sa");
			dataSource.setPassword("");
			Object result = new DatabaseConnectionVerifier(new MockEnvironment())
				.postProcessAfterInitialization(dataSource, "dataSource");
			assertThat(result).isSameAs(dataSource);
		}

		assertThat(output.getOut()).contains(
			"jdbcUrl=jdbc:h2:mem:connection-verifier",
			"Database connectivity verified: SELECT 1 returned 1 (database=H2)");
	}

	@Test
	void logsJdbcCausesAndChainedExceptionsWithoutSecrets(CapturedOutput output) throws Exception {
		var environment = new MockEnvironment()
			.withProperty("SPRING_DATASOURCE_PASSWORD", "database-secret")
			.withProperty("JWT_SECRET", "jwt-secret")
			.withProperty("INTERNAL_TOKEN", "internal-secret");
		SQLException failure = new SQLException("password=database-secret; jwt-secret", "08001", 7,
			new IllegalStateException("Connector IAM permission denied: Authorization: Bearer oauth-secret; Proxy-Authorization: Basic Zm9vOmJhcg=="));
		failure.setNextException(new SQLException("access_token=another-secret; internal-secret", "28000"));
		failure.addSuppressed(new IllegalArgumentException("client_secret=client-value"));
		HikariDataSource dataSource = mock(HikariDataSource.class);
		when(dataSource.getJdbcUrl()).thenReturn(
			"jdbc:postgresql://user:database-secret@host/ax?password=database-secret&access_token=another-secret");
		when(dataSource.getDataSourceProperties()).thenReturn(new java.util.Properties());
		when(dataSource.getConnection()).thenThrow(failure);

		assertThatThrownBy(() -> new DatabaseConnectionVerifier(environment)
			.postProcessAfterInitialization(dataSource, "dataSource"))
			.isInstanceOf(BeanCreationException.class)
			.hasStackTraceContaining("java.sql.SQLException [SQLState=08001, errorCode=7]")
			.hasStackTraceContaining("Connector IAM permission denied")
			.hasStackTraceContaining("SQLState=28000")
			.satisfies(error -> assertThat(stackTrace(error)).doesNotContain(
				"database-secret", "jwt-secret", "internal-secret", "oauth-secret", "another-secret", "client-value", "Zm9vOmJhcg=="));
		assertThat(output.getOut()).contains("Database SELECT 1 failed before Hibernate initialization",
			"jdbcUrl=jdbc:postgresql://[REDACTED]@host/ax",
			"SQLState=08001", "Connector IAM permission denied", "SQLState=28000", "[REDACTED]")
			.doesNotContain("database-secret", "jwt-secret", "internal-secret", "oauth-secret", "another-secret", "client-value", "Zm9vOmJhcg==");
	}

	@Test
	void jdbcFailureStopsConsumersBeforeTheyInitialize(CapturedOutput output) {
		new ApplicationContextRunner().withUserConfiguration(FailingDatabaseConfiguration.class)
			.run(context -> {
				assertThat(context).hasFailed().getFailure()
					.hasStackTraceContaining("Database SELECT 1 failed before Hibernate initialization")
					.hasStackTraceContaining("connection refused");
				assertThat(output.getOut()).doesNotContain("DATABASE_CONSUMER_INITIALIZED");
			});
	}

	private static String stackTrace(Throwable error) {
		var output = new java.io.StringWriter();
		error.printStackTrace(new java.io.PrintWriter(output));
		return output.toString();
	}

	@Configuration(proxyBeanMethods = false)
	static class FailingDatabaseConfiguration {
		@Bean
		static DatabaseConnectionVerifier verifier() {
			return new DatabaseConnectionVerifier(new MockEnvironment());
		}

		@Bean
		DataSource dataSource() throws Exception {
			DataSource dataSource = mock(DataSource.class);
			when(dataSource.getConnection()).thenThrow(new SQLException("connection refused", "08001"));
			return dataSource;
		}

		@Bean
		Object databaseConsumer(DataSource dataSource) {
			System.out.println("DATABASE_CONSUMER_INITIALIZED");
			return new Object();
		}
	}

}
