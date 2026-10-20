package com.example.demo;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class CloudSqlConfigurationTests {

	@Test
	void cloudSqlProfileBindsConnectorSettingsWithoutOpeningAConnection() {
		new ApplicationContextRunner()
			.withInitializer(new ConfigDataApplicationContextInitializer())
			.withPropertyValues(
				"spring.profiles.active=cloudsql",
				"SPRING_DATASOURCE_URL=jdbc:postgresql:///ax",
				"SPRING_DATASOURCE_USERNAME=axox_app",
				"SPRING_DATASOURCE_PASSWORD=test-only",
				"INSTANCE_CONNECTION_NAME=test-project:test-region:test-instance",
				"CLOUD_SQL_IP_TYPES=PRIVATE")
			.run(context -> {
				assertThat(context).hasNotFailed();
				HikariConfig config = Binder.get(context.getEnvironment())
					.bind("spring.datasource.hikari", Bindable.of(HikariConfig.class)).get();
				assertThat(config.getDataSourceProperties())
					.containsEntry("cloudSqlInstance", "test-project:test-region:test-instance")
					.containsEntry("ipTypes", "PRIVATE")
					.containsEntry("cloudSqlRefreshStrategy", "lazy");
				String socketFactory = config.getDataSourceProperties().getProperty("socketFactory");
				assertThatCode(() -> Class.forName(socketFactory)
					.asSubclass(javax.net.SocketFactory.class)).doesNotThrowAnyException();
			});
	}

}
