package com.axnavigator;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.axnavigator.support.IntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 포트로 서버를 띄워 DB 스키마 validate·보안 규칙을 확인한다. */
@ExtendWith(OutputCaptureExtension.class)
@IntegrationTest
class AxNavigatorApplicationTests {

	@LocalServerPort
	private int port;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void contextLoadsWithSchemaValidation() {
		assertThat(entityManagerFactory.isOpen()).isTrue();
	}

	@Test
	void startupVerifiesDatabaseWithAReadOnlyQuery(CapturedOutput output) {
		assertThat(output.getOut()).contains(
			"Database connectivity verified: SELECT 1 returned 1 (database=PostgreSQL)");
		assertThat(output.getOut().indexOf("Database connectivity verified: SELECT 1 returned 1"))
			.isLessThan(output.getOut().indexOf("Processing PersistenceUnitInfo"));
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
	void apiRequiresAuthentication() throws Exception {
		assertThat(get("/api/ai/tasks?mine=true").statusCode()).isEqualTo(401);
	}

	@Disabled("개발 중 permitAll 및 내부 토큰 필터 비활성화. 인증 복구 시 이 테스트도 다시 활성화한다.")
	@Test
	void internalRequiresToken() throws Exception {
		assertThat(get("/internal/solutions").statusCode()).isEqualTo(403);
	}

	private HttpResponse<String> get(String path) throws Exception {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
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
