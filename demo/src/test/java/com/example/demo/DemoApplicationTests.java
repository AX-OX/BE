package com.example.demo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DemoApplicationTests {

	@LocalServerPort
	private int port;

	@Test
	void contextLoads() {
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
