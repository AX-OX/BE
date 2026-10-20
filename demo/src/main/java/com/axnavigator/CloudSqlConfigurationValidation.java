package com.axnavigator;

import java.util.Properties;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
class CloudSqlConfigurationValidation {

	@Bean
	static BeanFactoryPostProcessor validateCloudSqlConfiguration(Environment environment) {
		return beanFactory -> {
			boolean cloudSql = environment.acceptsProfiles(Profiles.of("cloudsql"));
			if (environment.containsProperty("K_SERVICE") && !cloudSql) {
				throw new IllegalStateException(
					"Cloud Run requires SPRING_PROFILES_ACTIVE=cloudsql; check revision environment variables and command arguments");
			}
			if (!cloudSql) {
				return;
			}

			Properties properties = Binder.get(environment)
				.bind("spring.datasource.hikari.data-source-properties", Bindable.of(Properties.class))
				.orElseGet(Properties::new);
			if (!"com.google.cloud.sql.postgres.SocketFactory".equals(properties.getProperty("socketFactory"))) {
				throw new IllegalStateException(
					"cloudsql requires the Cloud SQL SocketFactory; check application-cloudsql.yml and SPRING_CONFIG overrides");
			}
			if (properties.getProperty("cloudSqlInstance", "").isBlank()) {
				throw new IllegalStateException("cloudsql requires INSTANCE_CONNECTION_NAME");
			}
			if (!java.util.Set.of("PUBLIC", "PRIVATE", "PSC").contains(properties.getProperty("ipTypes", ""))) {
				throw new IllegalStateException("cloudsql requires CLOUD_SQL_IP_TYPES=PUBLIC, PRIVATE or PSC");
			}
			String url = environment.getRequiredProperty("spring.datasource.url");
			if (!url.startsWith("jdbc:postgresql:")) {
				throw new IllegalStateException("cloudsql requires a PostgreSQL SPRING_DATASOURCE_URL");
			}
		};
	}

}
