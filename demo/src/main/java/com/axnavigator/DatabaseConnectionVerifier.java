package com.axnavigator;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
class DatabaseConnectionVerifier implements BeanPostProcessor {

	private static final Logger log = LoggerFactory.getLogger(DatabaseConnectionVerifier.class);

	private final Environment environment;

	DatabaseConnectionVerifier(Environment environment) {
		this.environment = environment;
	}

	@Override
	public Object postProcessAfterInitialization(Object bean, String beanName) {
		if (!(bean instanceof DataSource dataSource)) {
			return bean;
		}
		// ConfigurationProperties binding has finished. No DataSource is injected
		// into this post-processor, so it never creates an early, unbound pool.
		// Consumers (including Hibernate) receive this same instance only after
		// the query has succeeded.
		HikariDataSource hikari = dataSource instanceof HikariDataSource pool ? pool : null;
		var diagnostics = new DatabaseFailureDiagnostics(environment, hikari);
		if (hikari != null) {
			var properties = hikari.getDataSourceProperties();
			log.info("Database check before Hibernate: profiles={}, jdbcUrl={}, username={}, driver={}, socketFactory={}, cloudSqlInstance={}, ipTypes={}",
				diagnostics.redact(String.join(",", environment.getActiveProfiles())),
				diagnostics.safeJdbcUrl(hikari.getJdbcUrl()),
				diagnostics.redact(hikari.getUsername()),
				diagnostics.redact(hikari.getDriverClassName()),
				diagnostics.redact(properties.getProperty("socketFactory")),
				diagnostics.redact(properties.getProperty("cloudSqlInstance")),
				diagnostics.redact(properties.getProperty("ipTypes")));
		}
		try (var connection = dataSource.getConnection();
			var statement = connection.createStatement()) {
			statement.setQueryTimeout(10);
			try (var result = statement.executeQuery("SELECT 1")) {
				if (!result.next() || result.getInt(1) != 1) {
					throw new IllegalStateException("Database connectivity check did not return 1");
				}
			}
			log.info("Database connectivity verified: SELECT 1 returned 1 (database={})",
				diagnostics.redact(connection.getMetaData().getDatabaseProductName()));
		}
		catch (Exception failure) {
			// Never attach the raw exception to the bean failure: Spring would log
			// its unredacted messages again while shutting down the application.
			RuntimeException safeFailure = diagnostics.sanitize(failure);
			log.error("Database SELECT 1 failed before Hibernate initialization; JDBC/Connector cause chain follows", safeFailure);
			throw new BeanCreationException(beanName,
				"Database SELECT 1 failed before Hibernate initialization", safeFailure);
		}
		return bean;
	}

}
