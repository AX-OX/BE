package com.axnavigator;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CloudSqlConfigurationTests {

	private final ApplicationContextRunner cloudRun = new ApplicationContextRunner()
		.withInitializer(new ConfigDataApplicationContextInitializer())
		.withUserConfiguration(CloudSqlConfigurationValidation.class)
		.withPropertyValues(
			"K_SERVICE=backend-test",
			"SPRING_DATASOURCE_URL=jdbc:postgresql:///ax",
			"SPRING_DATASOURCE_USERNAME=axox_app",
			"SPRING_DATASOURCE_PASSWORD=test-only",
			"INSTANCE_CONNECTION_NAME=axox-db-trial:asia-northeast1:free-trial-first-project",
			"CLOUD_SQL_IP_TYPES=PUBLIC");

	@ParameterizedTest
	@ValueSource(strings = { "PUBLIC", "PRIVATE" })
	void cloudSqlProfileBindsConnectorSettingsWithoutOpeningAConnection(String ipType) {
		cloudRun
			.withPropertyValues(
				"spring.profiles.active=cloudsql",
				"CLOUD_SQL_IP_TYPES=" + ipType)
			.run(context -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getEnvironment().getProperty("spring.datasource.url"))
					.isEqualTo("jdbc:postgresql:///ax");
				HikariConfig config = Binder.get(context.getEnvironment())
					.bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();
				assertThat(config.getDataSourceProperties())
					.containsEntry("cloudSqlInstance", "axox-db-trial:asia-northeast1:free-trial-first-project")
					.containsEntry("ipTypes", ipType)
					.containsEntry("cloudSqlRefreshStrategy", "lazy");
				String socketFactory = config.getDataSourceProperties().getProperty("socketFactory");
				assertThatCode(() -> Class.forName(socketFactory)
					.asSubclass(javax.net.SocketFactory.class)).doesNotThrowAnyException();
			});
	}

	@Test
	void cloudRunWithoutCloudSqlProfileFailsBeforeOpeningLocalhost() {
		cloudRun.withPropertyValues("spring.profiles.active=default").run(context ->
			assertThat(context).hasFailed().getFailure()
				.hasMessageContaining("SPRING_PROFILES_ACTIVE=cloudsql"));
	}

	@Test
	void missingProfileConfigurationFailsBeforeOpeningLocalhost() {
		cloudRun.withPropertyValues(
			"spring.profiles.active=cloudsql",
			"spring.config.location=optional:classpath:/missing-deployment-config.yml")
			.run(context -> assertThat(context).hasFailed().getFailure()
				.hasMessageContaining("Cloud SQL SocketFactory"));
	}

	@Test
	void overriddenSocketFactoryIsRejectedBeforeOpeningLocalhost() {
		cloudRun.withPropertyValues(
			"spring.profiles.active=cloudsql",
			"spring.datasource.hikari.data-source-properties.socketFactory=javax.net.DefaultSocketFactory")
			.run(context -> assertThat(context).hasFailed().getFailure()
				.hasMessageContaining("Cloud SQL SocketFactory"));
	}

}
