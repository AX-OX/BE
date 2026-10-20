package com.axnavigator.ax.calc;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.axnavigator.ax.calc.CalcInputs.CompanyInput;
import com.axnavigator.ax.calc.CalcInputs.MetricInput;
import com.axnavigator.ax.calc.CalcResults.BaseValues;
import com.axnavigator.ax.calc.CalcResults.CandidateScore;
import com.axnavigator.ax.calc.CalcResults.Evaluation;
import com.axnavigator.ax.calc.CalcResults.PortfolioResult;
import com.axnavigator.ax.calc.CalcResults.Scenario;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 정답 = test resources ax/eval/demo_expected_portfolio.json. 기대값은 파일에서 읽어 비교하고 하드코딩하지 않는다.
 * 점수·비율은 BigDecimal compareTo로 소수 자리까지, 금액은 원 단위로 정확히 비교한다.
 */
class AxCalculatorTest {

	static final LocalDate AS_OF = LocalDate.of(2026, 10, 1);

	static AxPolicy policy;

	static JsonNode expected;

	static CompanyInput company;

	@BeforeAll
	static void load() throws IOException {
		try (InputStream in = AxCalculatorTest.class.getResourceAsStream("/ax/config/ax_score_policy_v1.json")) {
			policy = AxPolicy.read(in);
		}
		try (InputStream in = AxCalculatorTest.class.getResourceAsStream("/ax/eval/demo_expected_portfolio.json")) {
			expected = JsonMapper.builder().build().readTree(in);
		}
		company = SeedCsv.company(1);
	}

	@Test
	void evaluationDateMatchesExpectedFile() {
		assertThat(LocalDate.parse(expected.path("evaluation_as_of").asString())).isEqualTo(AS_OF);
		assertThat(AxCalculator.recentCompletedMonths(AS_OF)).containsExactly(YearMonth.of(2026, 7),
				YearMonth.of(2026, 8), YearMonth.of(2026, 9));
	}

	@Test
	void perCandidateMatchesExpectedFileExactly() {
		Evaluation evaluation = AxCalculator.evaluate(company, policy, AS_OF);
		JsonNode expectedCandidates = expected.path("per_candidate");

		assertThat(evaluation.eligible()).hasSize(expectedCandidates.size());
		Map<Long, CandidateScore> actual = evaluation.eligible()
			.stream()
			.collect(Collectors.toMap(CandidateScore::solutionId, Function.identity()));
		for (JsonNode e : expectedCandidates) {
			CandidateScore a = actual.get(e.path("solutionId").asLong());
			String at = "solution " + e.path("solutionId").asLong();
			assertThat(a).as(at).isNotNull();
			assertThat(a.processId()).as(at + " processId").isEqualTo(e.path("processId").asLong());
			assertThat(a.dependsOnSolutionId()).as(at + " dependsOnSolutionId")
				.isEqualTo(e.path("dependsOnSolutionId").isNull() ? null : e.path("dependsOnSolutionId").asLong());
			assertDecimal(a.axScore(), e.path("axScore"), at + " axScore");
			assertDecimal(a.impactScore(), e.path("impactScore"), at + " impactScore");
			assertDecimal(a.feasibilityScore(), e.path("feasibilityScore"), at + " feasibilityScore");
			assertDecimal(a.savedHoursBase(), e.path("savedHoursBase"), at + " savedHoursBase");
			assertThat(a.apiOk()).as(at + " apiOk").isEqualTo(e.path("apiOk").asBoolean());
			assertThat(a.humanReviewRequired()).as(at + " humanReviewRequired")
				.isEqualTo(e.path("humanReviewRequired").asBoolean());
			assertThat(a.dataConfidence().name()).as(at + " dataConfidence")
				.isEqualTo(e.path("dataConfidence").asString());
			assertThat(a.warnings()).as(at + " warnings").isEqualTo(strings(e.path("warnings")));
			assertThat(a.firstYearTcoKrw()).as(at + " firstYearTcoKrw").isEqualTo(e.path("firstYearTcoKrw").asLong());
			assertScenario(a.scenarios().low(), e.path("scenarios").path("low"), at + " low");
			assertScenario(a.scenarios().base(), e.path("scenarios").path("base"), at + " base");
			assertScenario(a.scenarios().high(), e.path("scenarios").path("high"), at + " high");
		}
	}

	@Test
	void normalizationMaxMatchesExpectedFile() {
		Evaluation evaluation = AxCalculator.evaluate(company, policy, AS_OF);
		assertDecimal(AxCalculator.round(evaluation.maxSavedHours(), 4), expected.path("normalization_max_saved_hours"),
				"normalization max");
	}

	@Test
	void calculationDetailsMatchExpectedFile() {
		Map<Long, List<MetricInput>> byProcess = company.metrics()
			.stream()
			.collect(Collectors.groupingBy(MetricInput::processId));
		Map<Long, Long> processOfSolution = company.solutions()
			.stream()
			.collect(Collectors.toMap(CalcInputs.SolutionInput::solutionId, CalcInputs.SolutionInput::targetProcessId));
		for (JsonNode d : expected.path("calculation_details")) {
			long processId = processOfSolution.get(d.path("solution_id").asLong());
			BaseValues base = AxCalculator.baseValues(processId, byProcess.get(processId), AS_OF);
			String at = "solution " + d.path("solution_id").asLong();
			assertDecimal(AxCalculator.round(base.annualHours(), 4), d.path("annual_hours"), at + " annual_hours");
			assertDecimal(AxCalculator.round(base.weightedHourlyLaborCost(), 4), d.path("labor_cost"),
					at + " labor_cost");
			assertThat(base.months().stream().map(MetricInput::metricId).sorted().toList())
				.as(at + " metric_ids")
				.isEqualTo(StreamSupport.stream(d.path("metric_ids").spliterator(), false)
					.map(JsonNode::asLong)
					.sorted()
					.toList());
		}
	}

	@Test
	void portfolioMatchesExpectedFile() {
		PortfolioResult result = AxCalculator.optimize(AxCalculator.evaluate(company, policy, AS_OF), null,
				policy.maxItems());

		assertThat(result.insufficient()).isFalse();
		assertThat(result.budgetKrw()).isEqualTo(expected.path("budget_limit_krw").asLong());
		assertThat(result.selected().stream().map(CandidateScore::solutionId).toList())
			.isEqualTo(longs(expected.path("selected_solution_ids")));
		assertThat(result.selected().stream().map(CandidateScore::processId).toList())
			.isEqualTo(longs(expected.path("selected_process_ids")));
		assertDecimal(result.totalAxScore(), expected.path("total_ax_score"), "totalAxScore");
		assertThat(result.totalFirstYearTcoKrw()).isEqualTo(expected.path("total_first_year_tco_krw").asLong());
		assertThat(result.remainingBudgetKrw()).isEqualTo(expected.path("remaining_budget_krw").asLong());
		assertThat(result.evaluatedCombinations()).isEqualTo(expected.path("evaluated_combinations").asInt());
		assertThat(result.validCombinations()).isEqualTo(expected.path("valid_combinations").asInt());

		JsonNode runnerUp = expected.path("runner_up");
		assertThat(result.runnerUp().solutionIds()).isEqualTo(longs(runnerUp.path("solution_ids")));
		assertDecimal(result.runnerUp().totalAxScore(), runnerUp.path("total_ax_score"), "runnerUp");
		assertDecimal(result.totalAxScore().subtract(result.runnerUp().totalAxScore()),
				expected.path("runner_up_score_gap"), "runnerUp gap");

		assertThat(result.excluded()).hasSize(expected.path("excluded").size());
		for (int i = 0; i < result.excluded().size(); i++) {
			assertThat(result.excluded().get(i).processId())
				.isEqualTo(expected.path("excluded").get(i).path("processId").asLong());
			assertThat(result.excluded().get(i).reason())
				.isEqualTo(expected.path("excluded").get(i).path("reason").asString());
		}
		assertThat(result.warnings()).isEmpty();
	}

	@Test
	void greedyTop3FailsBudgetAndDependency() {
		PortfolioResult result = AxCalculator.optimize(AxCalculator.evaluate(company, policy, AS_OF), null,
				policy.maxItems());
		JsonNode greedy = expected.path("greedy_top3_by_score");

		assertThat(result.greedyTop3().solutionIds()).isEqualTo(longs(greedy.path("solution_ids")));
		assertDecimal(result.greedyTop3().totalAxScore(), greedy.path("total_ax_score"), "greedy total");
		assertThat(result.greedyTop3().totalFirstYearTcoKrw())
			.isEqualTo(greedy.path("total_first_year_tco_krw").asLong());
		assertThat(result.greedyTop3().violations()).isEqualTo(strings(greedy.path("violations")))
			.containsExactly(AxCalculator.BUDGET_EXCEEDED, AxCalculator.MISSING_DEPENDENCY);
	}

	@Test
	void requestedCandidatesDoNotRenormalizeImpact() {
		Evaluation evaluation = AxCalculator.evaluate(company, policy, AS_OF);
		PortfolioResult subset = AxCalculator.optimize(evaluation, List.of(501L, 502L, 504L, 509L), 3);
		CandidateScore full504 = evaluation.eligible().stream().filter(c -> c.solutionId() == 504).findFirst().get();
		CandidateScore subset504 = subset.selected().stream().filter(c -> c.solutionId() == 504).findFirst().get();

		// 504는 부분 후보 중 saved_hours 최대지만 Impact는 전체 기준(507 = 100) 그대로다
		assertThat(subset504.impactScore()).isEqualByComparingTo(full504.impactScore());
		assertThat(subset504.impactScore()).isLessThan(new BigDecimal("100"));
	}

	@Test
	void zeroBudgetIsInsufficient() {
		CompanyInput noBudget = new CompanyInput(company.companyId(), 0, company.processes(), company.metrics(),
				company.systems(), company.links(), company.solutions());
		PortfolioResult result = AxCalculator.optimize(AxCalculator.evaluate(noBudget, policy, AS_OF), null, 3);

		assertThat(result.insufficient()).isTrue();
		assertThat(result.selected()).isEmpty();
		assertThat(result.validCombinations()).isZero();
		assertThat(result.evaluatedCombinations()).isEqualTo(56);
		assertThat(result.runnerUp()).isNull();
		assertThat(result.remainingBudgetKrw()).isZero();
	}

	@Test
	void dependentSolutionWithoutPrerequisiteIsRejected() {
		Evaluation evaluation = AxCalculator.evaluate(company, policy, AS_OF);

		PortfolioResult only508 = AxCalculator.optimize(evaluation, List.of(508L), 1);
		assertThat(only508.insufficient()).isTrue();
		assertThat(only508.selected()).isEmpty();
		assertThat(only508.greedyTop3().violations()).containsExactly(AxCalculator.MISSING_DEPENDENCY);

		PortfolioResult with509 = AxCalculator.optimize(evaluation, List.of(508L, 509L), 2);
		assertThat(with509.insufficient()).isFalse();
		assertThat(with509.selected().stream().map(CandidateScore::solutionId)).containsExactly(508L, 509L);
	}

	@Test
	void missingRecentMonthsMakeCandidateIneligible() {
		// 207(507)의 2026-08 지표를 제거 → 507은 INSUFFICIENT_METRICS로 빠지고 Impact 정규화 max도 바뀐다
		List<MetricInput> metrics = new ArrayList<>(company.metrics());
		metrics.removeIf(m -> m.processId() == 207 && m.periodStart().equals(LocalDate.of(2026, 8, 1)));
		CompanyInput partial = new CompanyInput(company.companyId(), company.budgetKrw(), company.processes(),
				metrics, company.systems(), company.links(), company.solutions());

		Evaluation evaluation = AxCalculator.evaluate(partial, policy, AS_OF);
		assertThat(evaluation.eligible()).extracting(CandidateScore::solutionId).doesNotContain(507L);
		assertThat(evaluation.insufficient()).singleElement().satisfies(i -> {
			assertThat(i.solutionId()).isEqualTo(507);
			assertThat(i.missingMonths()).containsExactly(YearMonth.of(2026, 8));
		});

		PortfolioResult result = AxCalculator.optimize(evaluation, null, 3);
		assertThat(result.selected()).extracting(CandidateScore::solutionId).doesNotContain(507L);
		assertThat(result.warnings()).singleElement().satisfies(w -> {
			assertThat(w.code()).isEqualTo(AxCalculator.INSUFFICIENT_METRICS);
			assertThat(w.missingMonths()).containsExactly("2026-08");
		});
	}

	@Test
	void noCompleteMetricsMeansInsufficientPortfolio() {
		CompanyInput empty = new CompanyInput(company.companyId(), company.budgetKrw(), company.processes(),
				List.of(), company.systems(), company.links(), company.solutions());
		PortfolioResult result = AxCalculator.optimize(AxCalculator.evaluate(empty, policy, AS_OF), null, 3);

		assertThat(result.insufficient()).isTrue();
		assertThat(result.selected()).isEmpty();
		assertThat(result.evaluatedCombinations()).isZero();
		assertThat(result.warnings()).hasSize(8);
	}

	@Test
	void asOfBeforeSeptemberEndUsesEarlierMonths() {
		// 2026-09-30 기준이면 9월은 아직 끝나지 않았다 → 06~08 (시드에 6월이 없으므로 부족)
		assertThat(AxCalculator.recentCompletedMonths(LocalDate.of(2026, 9, 30))).containsExactly(
				YearMonth.of(2026, 6), YearMonth.of(2026, 7), YearMonth.of(2026, 8));
		Evaluation evaluation = AxCalculator.evaluate(company, policy, LocalDate.of(2026, 9, 30));
		assertThat(evaluation.eligible()).isEmpty();
		assertThat(evaluation.insufficient()).allSatisfy(i -> assertThat(i.missingMonths())
			.containsExactly(YearMonth.of(2026, 6)));
	}

	@Test
	void combinationsAreLexicographic() {
		assertThat(AxCalculator.combinations(List.of(1, 2, 3, 4), 2)).containsExactly(List.of(1, 2), List.of(1, 3),
				List.of(1, 4), List.of(2, 3), List.of(2, 4), List.of(3, 4));
		assertThat(AxCalculator.combinations(List.of(1, 2), 3)).isEmpty();
	}

	private static void assertScenario(Scenario actual, JsonNode expected, String at) {
		assertThat(actual.annualSavingKrw()).as(at + " annualSavingKrw").isEqualTo(expected.path("annualSavingKrw").asLong());
		assertDecimal(actual.roiPct(), expected.path("roiPct"), at + " roiPct");
		assertDecimal(actual.paybackMonths(), expected.path("paybackMonths"), at + " paybackMonths");
		assertThat(actual.threeYearNetKrw()).as(at + " threeYearNetKrw").isEqualTo(expected.path("threeYearNetKrw").asLong());
	}

	private static void assertDecimal(BigDecimal actual, JsonNode expected, String at) {
		if (expected.isNull()) {
			assertThat(actual).as(at).isNull();
			return;
		}
		assertThat(actual).as(at).isNotNull();
		assertThat(actual).as(at).isEqualByComparingTo(new BigDecimal(expected.asString()));
	}

	private static List<Long> longs(JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asLong).toList();
	}

	private static List<String> strings(JsonNode array) {
		return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
	}

}
