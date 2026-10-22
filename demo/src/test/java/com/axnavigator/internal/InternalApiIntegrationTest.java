package com.axnavigator.internal;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.axnavigator.support.IntegrationTest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 DB(db/init 시드)로 내부 API를 호출해 응답 형식·정답 파일 일치를 확인한다. */
@IntegrationTest
class InternalApiIntegrationTest {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static JsonNode expected;

	@LocalServerPort
	int port;

	@BeforeAll
	static void loadExpected() throws Exception {
		try (InputStream in = InternalApiIntegrationTest.class.getResourceAsStream("/ax/eval/demo_expected_portfolio.json")) {
			expected = JSON.readTree(in);
		}
	}

	@Test
	void company() throws Exception {
		JsonNode body = ok(get("/internal/companies/1"));
		assertThat(body.path("axBudgetKrw").asLong()).isEqualTo(300_000_000L);
		assertThat(body.path("departments").size()).isEqualTo(3);
		assertThat(body.path("departments").get(0).path("managerEmail").asString()).isEqualTo("demo.101@gaon.example");
	}

	@Test
	void unknownCompanyAndDepartmentAre404() throws Exception {
		HttpResponse<String> company = get("/internal/companies/99");
		assertThat(company.statusCode()).isEqualTo(404);
		assertThat(JSON.readTree(company.body()).path("hint").path("validIds").get(0).asLong()).isEqualTo(1);

		HttpResponse<String> dept = get("/internal/companies/1/processes?departmentId=99");
		assertThat(dept.statusCode()).isEqualTo(404);
		assertThat(JSON.readTree(dept.body()).path("hint").path("validIds").toString()).isEqualTo("[101,102,103]");
	}

	@Test
	void processesAndMetricsByDepartment() throws Exception {
		JsonNode processes = ok(get("/internal/companies/1/processes?departmentId=103"));
		assertThat(processes.size()).isEqualTo(3);
		assertThat(processes.get(0).path("processSteps").isArray()).isTrue();
		assertThat(processes.get(0).path("departmentName").asString()).isEqualTo("개발");

		JsonNode metrics = ok(get("/internal/companies/1/metrics"));
		assertThat(metrics.size()).isEqualTo(9);
		JsonNode first = metrics.get(0);
		assertThat(first.path("months").size()).isEqualTo(3);
		assertThat(first.path("months").get(0).path("periodStart").asString()).isEqualTo("2026-07-01");
		assertThat(first.path("dataConfidence").asString()).isEqualTo("ASSUMED");
		assertThat(first.path("missingMonths").size()).isZero();
		assertThat(first.path("annualHours").isNumber()).isTrue();
	}

	@Test
	void systemsAndSolutions() throws Exception {
		JsonNode systems = ok(get("/internal/companies/1/systems"));
		assertThat(systems.size()).isEqualTo(6);
		assertThat(systems.get(0).path("processes").isArray()).isTrue();

		JsonNode solutions = ok(get("/internal/solutions"));
		assertThat(solutions.size()).isEqualTo(9);
		JsonNode s508 = solutions.get(7);
		assertThat(s508.path("solutionId").asLong()).isEqualTo(508);
		assertThat(s508.path("dependsOnSolutionId").asLong()).isEqualTo(509);
		assertThat(s508.path("targetProcessId").asLong()).isEqualTo(208);
		assertThat(s508.path("automationRateBase").decimalValue()).isLessThanOrEqualTo(BigDecimal.ONE);
	}

	@Test
	void roiMatchesExpectedFile() throws Exception {
		for (JsonNode e : expected.path("per_candidate")) {
			JsonNode actual = ok(post("/internal/ax/roi", "{\"processId\":" + e.path("processId").asLong()
					+ ",\"solutionId\":" + e.path("solutionId").asLong() + "}"));
			assertThat(actual).as("solution " + e.path("solutionId")).isEqualTo(e);
		}
	}

	@Test
	void roiErrors() throws Exception {
		assertError(post("/internal/ax/roi", "{\"processId\":299,\"solutionId\":501}"), 404, "NOT_FOUND");
		assertError(post("/internal/ax/roi", "{\"processId\":201,\"solutionId\":599}"), 404, "NOT_FOUND");
		JsonNode mismatch = assertError(post("/internal/ax/roi", "{\"processId\":201,\"solutionId\":502}"), 400,
				"VALIDATION_ERROR");
		assertThat(mismatch.path("hint").path("expectedProcessId").asLong()).isEqualTo(202);
		JsonNode excluded = assertError(post("/internal/ax/roi", "{\"processId\":203,\"solutionId\":503}"), 400,
				"EXCLUDED_PROCESS");
		assertThat(excluded.path("hint").path("reason").asString())
			.isEqualTo(expected.path("excluded").get(0).path("reason").asString());
	}

	@Test
	void portfolioMatchesExpectedFile() throws Exception {
		JsonNode body = ok(post("/internal/ax/portfolio", "{\"companyId\":1,\"budgetKrw\":300000000}"));

		assertThat(body.path("insufficient").asBoolean()).isFalse();
		assertThat(body.path("budgetKrw").asLong()).isEqualTo(expected.path("budget_limit_krw").asLong());
		assertThat(body.path("selected").findValues("solutionId"))
			.isEqualTo(expected.path("selected_solution_ids").values().stream().toList());
		for (JsonNode selected : body.path("selected")) {
			JsonNode e = find(expected.path("per_candidate"), selected.path("solutionId").asLong());
			assertThat(selected).isEqualTo(e);
		}
		assertThat(body.path("totalAxScore").decimalValue())
			.isEqualByComparingTo(expected.path("total_ax_score").decimalValue());
		assertThat(body.path("totalFirstYearTcoKrw").asLong()).isEqualTo(expected.path("total_first_year_tco_krw").asLong());
		assertThat(body.path("remainingBudgetKrw").asLong()).isEqualTo(expected.path("remaining_budget_krw").asLong());
		assertThat(body.path("evaluatedCombinations").asInt()).isEqualTo(expected.path("evaluated_combinations").asInt());
		assertThat(body.path("validCombinations").asInt()).isEqualTo(expected.path("valid_combinations").asInt());
		assertThat(body.path("excluded")).isEqualTo(expected.path("excluded"));
		assertThat(body.path("runnerUp").path("solutionIds")).isEqualTo(expected.path("runner_up").path("solution_ids"));
		assertThat(body.path("runnerUp").path("totalAxScore").decimalValue())
			.isEqualByComparingTo(expected.path("runner_up").path("total_ax_score").decimalValue());
		JsonNode greedy = expected.path("greedy_top3_by_score");
		assertThat(body.path("greedyTop3").path("solutionIds")).isEqualTo(greedy.path("solution_ids"));
		assertThat(body.path("greedyTop3").path("violations")).isEqualTo(greedy.path("violations"));
		assertThat(body.path("greedyTop3").path("totalFirstYearTcoKrw").asLong())
			.isEqualTo(greedy.path("total_first_year_tco_krw").asLong());
	}

	@Test
	void portfolioWithoutBudgetSkipsBudgetConstraint() throws Exception {
		JsonNode body = ok(post("/internal/ax/portfolio", "{\"companyId\":1}"));

		assertThat(body.path("insufficient").asBoolean()).isFalse();
		assertThat(body.path("budgetKrw").isNull()).isTrue();
		assertThat(body.path("remainingBudgetKrw").isNull()).isTrue();
		assertThat(body.path("greedyTop3").path("violations").toString()).doesNotContain("BUDGET_EXCEEDED");
		assertThat(body.path("validCombinations").asInt()).isGreaterThan(expected.path("valid_combinations").asInt());
		assertError(post("/internal/ax/portfolio", "{\"companyId\":1,\"budgetKrw\":0}"), 400, "VALIDATION_ERROR");
	}

	@Test
	void portfolioWithCandidatesAndErrors() throws Exception {
		JsonNode only508 = ok(post("/internal/ax/portfolio",
				"{\"companyId\":1,\"candidates\":[{\"processId\":208,\"solutionId\":508}],\"maxItems\":1}"));
		assertThat(only508.path("insufficient").asBoolean()).isTrue();
		assertThat(only508.path("selected").size()).isZero();

		JsonNode withExcluded = ok(post("/internal/ax/portfolio", "{\"companyId\":1,\"candidates\":["
				+ "{\"processId\":203,\"solutionId\":503},{\"processId\":207,\"solutionId\":507},"
				+ "{\"processId\":209,\"solutionId\":509},{\"processId\":201,\"solutionId\":501}]}"));
		assertThat(withExcluded.path("selected").findValuesAsString("solutionId")).containsExactly("501", "507", "509");
		assertThat(withExcluded.path("excluded").get(0).path("processId").asLong()).isEqualTo(203);

		assertError(post("/internal/ax/portfolio", "{\"companyId\":99}"), 404, "NOT_FOUND");
		assertError(post("/internal/ax/portfolio",
				"{\"companyId\":1,\"candidates\":[{\"processId\":201,\"solutionId\":502}]}"), 400, "VALIDATION_ERROR");
	}

	private static JsonNode find(JsonNode candidates, long solutionId) {
		for (JsonNode c : candidates) {
			if (c.path("solutionId").asLong() == solutionId) {
				return c;
			}
		}
		throw new AssertionError("no expected candidate " + solutionId);
	}

	private static JsonNode ok(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return JSON.readTree(response.body());
	}

	private static JsonNode assertError(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
		JsonNode body = JSON.readTree(response.body());
		assertThat(body.path("code").asString()).isEqualTo(code);
		return body;
	}

	private HttpResponse<String> get(String path) throws Exception {
		return send(HttpRequest.newBuilder(uri(path)).GET());
	}

	private HttpResponse<String> post(String path, String json) throws Exception {
		return send(HttpRequest.newBuilder(uri(path))
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(json)));
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + port + path);
	}

	private static HttpResponse<String> send(HttpRequest.Builder builder) throws Exception {
		try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
			return client.send(builder.header(InternalTokenFilter.HEADER, "test-internal-token")
				.timeout(Duration.ofSeconds(10))
				.build(), HttpResponse.BodyHandlers.ofString());
		}
	}

}
