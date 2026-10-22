package com.axnavigator.ax.calc;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.axnavigator.ax.calc.CalcInputs.CompanyInput;
import com.axnavigator.ax.calc.CalcInputs.LinkInput;
import com.axnavigator.ax.calc.CalcInputs.MetricInput;
import com.axnavigator.ax.calc.CalcInputs.ProcessInput;
import com.axnavigator.ax.calc.CalcInputs.SolutionInput;
import com.axnavigator.ax.calc.CalcInputs.SystemInput;
import com.axnavigator.ax.calc.CalcResults.BaseValues;
import com.axnavigator.ax.calc.CalcResults.CandidateScore;
import com.axnavigator.ax.calc.CalcResults.CandidateWarning;
import com.axnavigator.ax.calc.CalcResults.Evaluation;
import com.axnavigator.ax.calc.CalcResults.Exclusion;
import com.axnavigator.ax.calc.CalcResults.GreedyTop;
import com.axnavigator.ax.calc.CalcResults.InsufficientCandidate;
import com.axnavigator.ax.calc.CalcResults.PortfolioResult;
import com.axnavigator.ax.calc.CalcResults.RunnerUp;
import com.axnavigator.ax.calc.CalcResults.Scenario;
import com.axnavigator.ax.calc.CalcResults.Scenarios;

/** 기준 문서 §6 계산 규칙. 상태가 없는 순수 함수 모음이다. */
public final class AxCalculator {

	public static final String LOW_STANDARDIZATION = "LOW_STANDARDIZATION";

	public static final String INSUFFICIENT_METRICS = "INSUFFICIENT_METRICS";

	public static final String BUDGET_EXCEEDED = "BUDGET_EXCEEDED";

	public static final String MISSING_DEPENDENCY = "MISSING_DEPENDENCY";

	// 같은 업무의 후보가 둘 이상인 단순 상위 조합의 제약 위반 사유.
	public static final String DUPLICATE_PROCESS = "DUPLICATE_PROCESS";

	/** 나눗셈 정밀도. 중간값은 반올림하지 않고 이 유효 자릿수로만 유지한다. */
	private static final MathContext MC = MathContext.DECIMAL128;

	private static final BigDecimal SIXTY = BigDecimal.valueOf(60);

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private static final BigDecimal TWELVE = BigDecimal.valueOf(12);

	private static final BigDecimal THREE = BigDecimal.valueOf(3);

	private static final BigDecimal FOUR = BigDecimal.valueOf(4);

	private static final BigDecimal THIRTY_SIX = BigDecimal.valueOf(36);

	private static final int MONTHS = 3;

	private AxCalculator() {
	}

	// ------------------------------------------------------------------
	// 평가 기준일 · 공통 기초값
	// ------------------------------------------------------------------

	/** 기준일 이전에 끝난 최근 3개 월 (오래된 월부터). 기준일 2026-10-01 → 2026-07, 08, 09. */
	public static List<YearMonth> recentCompletedMonths(LocalDate asOf) {
		LocalDate lastDay = asOf.minusDays(1);
		YearMonth latest = YearMonth.from(lastDay);
		if (!lastDay.equals(latest.atEndOfMonth())) {
			latest = latest.minusMonths(1);
		}
		List<YearMonth> months = new ArrayList<>();
		for (int i = MONTHS - 1; i >= 0; i--) {
			months.add(latest.minusMonths(i));
		}
		return months;
	}

	/**
	 * 업무 1개의 공통 기초값. 해당 월 전체(1일~말일)를 덮는 지표만 그 월의 지표로 본다. 없는 월은 만들지 않고 missingMonths에 담는다.
	 * @param processMetrics 이 업무의 지표 (다른 업무 지표는 무시)
	 */
	public static BaseValues baseValues(long processId, Collection<MetricInput> processMetrics, LocalDate asOf) {
		List<MetricInput> months = new ArrayList<>();
		List<YearMonth> missing = new ArrayList<>();
		for (YearMonth month : recentCompletedMonths(asOf)) {
			processMetrics.stream()
				.filter(m -> m.processId() == processId)
				.filter(m -> m.periodStart().equals(month.atDay(1)) && m.periodEnd().equals(month.atEndOfMonth()))
				.max(Comparator.comparingLong(MetricInput::metricId))
				.ifPresentOrElse(months::add, () -> missing.add(month));
		}
		if (!missing.isEmpty()) {
			return new BaseValues(processId, months, missing, null, null, null, lowest(months));
		}
		BigDecimal quarterHours = BigDecimal.ZERO;
		BigDecimal weightedCost = BigDecimal.ZERO;
		BigDecimal count = BigDecimal.ZERO;
		for (MetricInput m : months) {
			BigDecimal hours = monthlyHours(m);
			quarterHours = quarterHours.add(hours);
			weightedCost = weightedCost.add(hours.multiply(BigDecimal.valueOf(m.hourlyLaborCostKrw())));
			count = count.add(BigDecimal.valueOf(m.processingCount()));
		}
		BigDecimal annualHours = quarterHours.multiply(FOUR);
		BigDecimal laborCost = quarterHours.signum() > 0 ? weightedCost.divide(quarterHours, MC) : BigDecimal.ZERO;
		BigDecimal monthlyAvgCount = count.divide(BigDecimal.valueOf(MONTHS), MC);
		return new BaseValues(processId, months, List.of(), annualHours, laborCost, monthlyAvgCount, lowest(months));
	}

	/** monthly_hours_m = processing_count_m × avg_processing_minutes_m / 60 */
	private static BigDecimal monthlyHours(MetricInput m) {
		return BigDecimal.valueOf(m.processingCount()).multiply(m.avgProcessingMinutes()).divide(SIXTY, MC);
	}

	private static DataConfidence lowest(List<MetricInput> months) {
		return months.stream()
			.map(MetricInput::dataSource)
			.min(Comparator.naturalOrder())
			.orElse(null);
	}

	// ------------------------------------------------------------------
	// AX점수 · 보조 지표
	// ------------------------------------------------------------------

	/** 기업의 선정 가능 후보 전체를 평가한다. Impact 정규화 max는 이 전체 기준이다. */
	public static Evaluation evaluate(CompanyInput input, AxPolicy policy, LocalDate asOf) {
		Map<Long, ProcessInput> processes = input.processes()
			.stream()
			.collect(Collectors.toMap(ProcessInput::processId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
		Map<Long, SystemInput> systems = input.systems()
			.stream()
			.collect(Collectors.toMap(SystemInput::systemId, Function.identity()));
		Map<Long, List<MetricInput>> metricsByProcess = input.metrics()
			.stream()
			.collect(Collectors.groupingBy(MetricInput::processId));

		List<Exclusion> excluded = policy.exclusions()
			.entrySet()
			.stream()
			.filter(e -> processes.containsKey(e.getKey()))
			.map(e -> new Exclusion(e.getKey(), e.getValue()))
			.toList();

		List<SolutionInput> candidates = input.solutions()
			.stream()
			.filter(s -> processes.containsKey(s.targetProcessId()))
			.filter(s -> !policy.exclusions().containsKey(s.targetProcessId()))
			.sorted(Comparator.comparingLong(SolutionInput::solutionId))
			.toList();

		Map<Long, BaseValues> bases = new LinkedHashMap<>();
		List<InsufficientCandidate> insufficient = new ArrayList<>();
		List<SolutionInput> eligible = new ArrayList<>();
		Map<Long, BigDecimal> savedHours = new LinkedHashMap<>();
		for (SolutionInput s : candidates) {
			BaseValues base = bases.computeIfAbsent(s.targetProcessId(),
					pid -> baseValues(pid, metricsByProcess.getOrDefault(pid, List.of()), asOf));
			if (!base.sufficient()) {
				insufficient.add(new InsufficientCandidate(s.targetProcessId(), s.solutionId(), base.missingMonths()));
				continue;
			}
			eligible.add(s);
			savedHours.put(s.solutionId(), savedHours(base.annualHours(), s.automationRateBase(),
					s.timeReductionRateBase()));
		}

		BigDecimal max = savedHours.values().stream().max(Comparator.naturalOrder()).orElse(BigDecimal.ZERO);
		List<CandidateScore> scores = new ArrayList<>();
		for (SolutionInput s : eligible) {
			ProcessInput process = processes.get(s.targetProcessId());
			boolean apiOk = !s.requiresApi() || input.links()
				.stream()
				.filter(l -> l.processId() == s.targetProcessId())
				.map(LinkInput::systemId)
				.map(systems::get)
				.anyMatch(sys -> sys != null && sys.apiAvailable());
			scores.add(score(s, process, bases.get(s.targetProcessId()), savedHours.get(s.solutionId()), max, apiOk,
					policy));
		}
		return new Evaluation(input.companyId(), input.budgetKrw(), List.copyOf(scores), excluded,
				List.copyOf(insufficient), max);
	}

	/** saved_hours = annual_hours × automation_rate × time_reduction_rate */
	private static BigDecimal savedHours(BigDecimal annualHours, BigDecimal automationRate,
			BigDecimal timeReductionRate) {
		return annualHours.multiply(automationRate).multiply(timeReductionRate);
	}

	private static CandidateScore score(SolutionInput s, ProcessInput process, BaseValues base, BigDecimal saved,
			BigDecimal max, boolean apiOk, AxPolicy policy) {
		BigDecimal impact = max.signum() > 0 ? saved.multiply(HUNDRED).divide(max, MC) : BigDecimal.ZERO;
		BigDecimal standardization = process.standardizationLevel() == null ? BigDecimal.ZERO
				: process.standardizationLevel();
		BigDecimal feasibility = standardization.multiply(policy.standardizationMultiplier())
			.add(apiOk ? policy.apiOkPoints() : BigDecimal.ZERO);
		BigDecimal axScore = impact.multiply(policy.impactWeight()).add(feasibility.multiply(policy.feasibilityWeight()));

		List<String> warnings = new ArrayList<>();
		if (process.standardizationLevel() != null && s.minStandardizationLevel() != null
				&& process.standardizationLevel().compareTo(s.minStandardizationLevel()) < 0) {
			warnings.add(LOW_STANDARDIZATION);
		}

		BigDecimal build = BigDecimal.valueOf(s.baseBuildCostKrw());
		BigDecimal annualRun = BigDecimal.valueOf(s.monthlyRunCostKrw()).multiply(TWELVE);
		BigDecimal tco = build.add(annualRun);
		Scenarios scenarios = new Scenarios(
				scenario(base, s.automationRateLow(), s.timeReductionRateLow(), s, build, annualRun, tco),
				scenario(base, s.automationRateBase(), s.timeReductionRateBase(), s, build, annualRun, tco),
				scenario(base, s.automationRateHigh(), s.timeReductionRateHigh(), s, build, annualRun, tco));

		return new CandidateScore(s.targetProcessId(), s.solutionId(), s.dependsOnSolutionId(),
				round(axScore, policy.scoreScale()), round(impact, 1), round(feasibility, 1), round(saved, 2), apiOk,
				process.requiresApproval(), base.dataConfidence(), List.copyOf(warnings), money(tco), scenarios);
	}

	private static Scenario scenario(BaseValues base, BigDecimal automationRate, BigDecimal timeReductionRate,
			SolutionInput s, BigDecimal build, BigDecimal annualRun, BigDecimal tco) {
		// annual_saving_s = saved_hours_s × labor_cost (중간 반올림 없음)
		BigDecimal saving = savedHours(base.annualHours(), automationRate, timeReductionRate)
			.multiply(base.weightedHourlyLaborCost());
		BigDecimal roiPct = tco.signum() > 0 ? saving.subtract(tco).multiply(HUNDRED).divide(tco, MC) : null;
		BigDecimal paybackDenominator = saving.subtract(annualRun);
		BigDecimal payback = paybackDenominator.signum() > 0
				? round(TWELVE.multiply(build).divide(paybackDenominator, MC), 1) : null;
		BigDecimal threeYearNet = THREE.multiply(saving)
			.subtract(build)
			.subtract(THIRTY_SIX.multiply(BigDecimal.valueOf(s.monthlyRunCostKrw())));
		return new Scenario(money(saving), roiPct == null ? null : round(roiPct, 2), payback, money(threeYearNet));
	}

	// ------------------------------------------------------------------
	// 포트폴리오
	// ------------------------------------------------------------------

	/**
	 * 크기 maxItems 조합 전수 탐색. 제약: 초년도 TCO 합 ≤ 예산, 업무 중복 금지, 선행 솔루션 동반.
	 * 목표: 반올림된 AX점수 합 최대 → 기준 3년 순편익 합 최대 → 초년도 TCO 합 최소.
	 * @param requestedSolutionIds null 또는 비어 있으면 선정 가능 후보 전체
	 */
	public static PortfolioResult optimize(Evaluation evaluation, Collection<Long> requestedSolutionIds,
			int maxItems) {
		boolean all = requestedSolutionIds == null || requestedSolutionIds.isEmpty();
		Set<Long> requested = all ? Set.of() : new HashSet<>(requestedSolutionIds);
		List<CandidateScore> pool = evaluation.eligible()
			.stream()
			.filter(c -> all || requested.contains(c.solutionId()))
			.sorted(Comparator.comparingLong(CandidateScore::solutionId))
			.toList();
		List<CandidateWarning> warnings = evaluation.insufficient()
			.stream()
			.filter(i -> all || requested.contains(i.solutionId()))
			.map(i -> new CandidateWarning(i.processId(), i.solutionId(), INSUFFICIENT_METRICS,
					i.missingMonths().stream().map(YearMonth::toString).toList()))
			.toList();
		Long budget = evaluation.budgetKrw();

		List<List<CandidateScore>> combinations = combinations(pool, maxItems);
		List<List<CandidateScore>> valid = new ArrayList<>(
				combinations.stream().filter(c -> violations(c, budget).isEmpty()).toList());
		// List.sort는 안정 정렬: 완전 동점이면 조합 생성 순서(solutionId 사전순)를 유지
		valid.sort(Comparator.comparing(AxCalculator::totalAxScore)
			.reversed()
			.thenComparing(Comparator.comparingLong(AxCalculator::totalThreeYearNetBase).reversed())
			.thenComparingLong(AxCalculator::totalTco));

		List<CandidateScore> greedy = pool.stream()
			.sorted(Comparator.comparing(CandidateScore::axScore)
				.reversed()
				.thenComparingLong(CandidateScore::solutionId))
			.limit(maxItems)
			.toList();
		GreedyTop greedyTop = new GreedyTop(ids(greedy), totalAxScore(greedy), totalTco(greedy),
				violations(greedy, budget));

		if (valid.isEmpty()) {
			return new PortfolioResult(true, budget, List.of(), BigDecimal.ZERO.setScale(1), 0, budget,
					combinations.size(), 0, evaluation.excluded(), null, greedyTop, warnings);
		}
		List<CandidateScore> best = valid.get(0);
		RunnerUp runnerUp = valid.size() > 1
				? new RunnerUp(ids(valid.get(1)), totalAxScore(valid.get(1))) : null;
		long tco = totalTco(best);
		Long remaining = budget == null ? null : budget - tco;
		return new PortfolioResult(false, budget, best, totalAxScore(best), tco, remaining, combinations.size(),
				valid.size(), evaluation.excluded(), runnerUp, greedyTop, warnings);
	}

	/** 조합 제약 위반 사유. 비어 있으면 유효한 조합이다. budgetKrw가 null이면 예산은 검사하지 않는다. */
	public static List<String> violations(List<CandidateScore> combination, Long budgetKrw) {
		List<String> violations = new ArrayList<>();
		if (budgetKrw != null && totalTco(combination) > budgetKrw) {
			violations.add(BUDGET_EXCEEDED);
		}
		Set<Long> ids = new HashSet<>(ids(combination));
		if (combination.stream()
			.anyMatch(c -> c.dependsOnSolutionId() != null && !ids.contains(c.dependsOnSolutionId()))) {
			violations.add(MISSING_DEPENDENCY);
		}
		if (combination.stream().map(CandidateScore::processId).distinct().count() != combination.size()) {
			violations.add(DUPLICATE_PROCESS);
		}
		return violations;
	}

	/** itertools.combinations와 같은 사전순 조합. */
	static <T> List<List<T>> combinations(List<T> items, int size) {
		List<List<T>> result = new ArrayList<>();
		if (size <= 0 || size > items.size()) {
			return result;
		}
		collect(items, size, 0, new ArrayList<>(), result);
		return result;
	}

	private static <T> void collect(List<T> items, int size, int start, List<T> current, List<List<T>> out) {
		if (current.size() == size) {
			out.add(List.copyOf(current));
			return;
		}
		for (int i = start; i <= items.size() - (size - current.size()); i++) {
			current.add(items.get(i));
			collect(items, size, i + 1, current, out);
			current.remove(current.size() - 1);
		}
	}

	private static List<Long> ids(List<CandidateScore> combination) {
		return combination.stream().map(CandidateScore::solutionId).toList();
	}

	/** 포트폴리오 목표: 반올림된(응답) AX점수의 합. */
	private static BigDecimal totalAxScore(List<CandidateScore> combination) {
		return combination.stream()
			.map(CandidateScore::axScore)
			.reduce(BigDecimal.ZERO.setScale(1), BigDecimal::add);
	}

	private static long totalThreeYearNetBase(List<CandidateScore> combination) {
		return combination.stream().mapToLong(c -> c.scenarios().base().threeYearNetKrw()).sum();
	}

	private static long totalTco(List<CandidateScore> combination) {
		return combination.stream().mapToLong(CandidateScore::firstYearTcoKrw).sum();
	}

	// ------------------------------------------------------------------
	// 반올림 (응답 값을 만들 때만)
	// ------------------------------------------------------------------

	public static BigDecimal round(BigDecimal value, int scale) {
		return value.setScale(scale, RoundingMode.HALF_UP);
	}

	/** 원 단위 (식 전체 계산 후 한 번만). */
	public static long money(BigDecimal value) {
		return value.setScale(0, RoundingMode.HALF_UP).longValueExact();
	}

}
