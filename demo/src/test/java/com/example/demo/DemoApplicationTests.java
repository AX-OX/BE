package com.example.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
@ActiveProfiles("test")
@SpringBootTest(
	webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
	properties = {
		"spring.datasource.url=jdbc:h2:mem:backend-tests;DB_CLOSE_DELAY=-1",
		"spring.datasource.username=sa",
		"spring.datasource.password=",
		"spring.datasource.driver-class-name=org.h2.Driver"
	})
class DemoApplicationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void startupVerifiesDatabaseWithAReadOnlyQuery(CapturedOutput output) {
		assertThat(output.getOut()).contains(
			"Database connectivity verified: SELECT 1 returned 1 (database=H2)");
	}

	@Test
	void jpaCanQueryTheTestDatabase() {
		try (var entityManager = entityManagerFactory.createEntityManager()) {
			assertThat(((Number) entityManager.createNativeQuery("SELECT 1")
				.getSingleResult()).intValue()).isEqualTo(1);
		}
	}

	@Test
	void healthIsAvailableWithoutAuthentication() throws Exception {
		HttpResponse<String> response = get("/health");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.headers().firstValue("Content-Type").orElse(""))
			.startsWith("application/json");
		assertThat(response.body()).isEqualTo("{\"status\":\"UP\"}");
	}

	@Test
	void otherEndpointsStillRequireAuthentication() throws Exception {
		assertThat(get("/private").statusCode()).isEqualTo(401);
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newBuilder()
			.connectTimeout(Duration.ofSeconds(5)).build()) {
			HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://127.0.0.1:" + port + path))
				.header("Accept", "application/json")
				.timeout(Duration.ofSeconds(5))
				.GET()
				.build();
			return client.send(request, HttpResponse.BodyHandlers.ofString());
		}
	}

}
