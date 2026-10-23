package com.axnavigator.ax;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.YearMonth;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.axnavigator.ax.calc.AxCalculator;
import com.axnavigator.ax.calc.AxPolicy;
import com.axnavigator.ax.calc.CalcInputs.CompanyInput;
import com.axnavigator.ax.calc.CalcInputs.LinkInput;
import com.axnavigator.ax.calc.CalcInputs.MetricInput;
import com.axnavigator.ax.calc.CalcInputs.ProcessInput;
import com.axnavigator.ax.calc.CalcInputs.SolutionInput;
import com.axnavigator.ax.calc.CalcInputs.SystemInput;
import com.axnavigator.ax.calc.CalcResults.BaseValues;
import com.axnavigator.ax.calc.CalcResults.CandidateKey;
import com.axnavigator.ax.calc.CalcResults.CandidateScore;
import com.axnavigator.ax.calc.CalcResults.Evaluation;
import com.axnavigator.ax.calc.CalcResults.InsufficientCandidate;
import com.axnavigator.ax.calc.CalcResults.PortfolioResult;
import com.axnavigator.common.ApiException;
import com.axnavigator.common.AxProperties;
import com.axnavigator.common.ErrorCode;
import com.axnavigator.company.AiSolution;
import com.axnavigator.company.AiSolutionRepository;
import com.axnavigator.company.BusinessProcess;
import com.axnavigator.company.BusinessProcessRepository;
import com.axnavigator.company.CompanyData;
import com.axnavigator.company.CompanyDataLoader;
import com.axnavigator.company.Department;
import com.axnavigator.company.DepartmentRepository;
import com.axnavigator.company.ProcessMetric;
import com.axnavigator.company.WorkType;

/** DB에서 계산 입력을 만들어 순수 계산 모듈(AxCalculator)에 넘긴다. AX점수·ROI·포트폴리오 계산은 이 서비스에만 있다. */
@Service
public class AxCalcService {

	private final CompanyDataLoader loader;

	private final BusinessProcessRepository processes;

	private final DepartmentRepository departments;

	private final AiSolutionRepository solutions;

	private final AxPolicy policy;

	private final LocalDate configuredAsOf;
	private final ZoneId zone;

	public AxCalcService(CompanyDataLoader loader, BusinessProcessRepository processes,
			DepartmentRepository departments, AiSolutionRepository solutions, AxPolicy policy,
			AxProperties properties) {
		this.loader = loader;
		this.processes = processes;
		this.departments = departments;
		this.solutions = solutions;
		this.policy = policy;
		this.configuredAsOf = properties.evaluation() == null ? null : properties.evaluation().asOf();
		this.zone = ZoneId.of(properties.timeZone());
	}

	public AxPolicy policy() {
		return policy;
	}

	/** 설정한 기준일이 없으면 오늘. 최근 3개 완료 월은 이 날짜 이전 달까지다. */
	public LocalDate asOf() {
		return configuredAsOf != null ? configuredAsOf : LocalDate.now(zone);
	}

	/** budgetKrw가 null이면 예산 제약 없이 평가한다. */
	@Transactional(readOnly = true)
	public Evaluation evaluate(Long companyId, Long budgetKrw) {
		return AxCalculator.evaluate(toInput(loader.load(companyId), budgetKrw), policy, asOf());
	}

	/** 업무별 최근 3개 완료 월 기초값 (지표 조회 API용). */
	public Map<Long, BaseValues> baseValues(CompanyData data) {
		Map<Long, List<MetricInput>> byProcess = data.metrics()
			.stream()
			.map(AxCalcService::toMetric)
			.collect(Collectors.groupingBy(MetricInput::processId));
		return data.processes()
			.stream()
			.collect(Collectors.toMap(BusinessProcess::getProcessId, p -> AxCalculator.baseValues(p.getProcessId(),
					byProcess.getOrDefault(p.getProcessId(), List.of()), asOf())));
	}

	/** POST /internal/ax/roi — 후보 1개의 AX점수 + 보조 지표. 오류는 §7 "/internal/ax/roi 오류" 표. */
	@Transactional(readOnly = true)
	public CandidateScore roi(Long processId, Long solutionId) {
		BusinessProcess process = processes.findById(processId)
			.orElseThrow(() -> ApiException.notFound("업무 " + processId + "는 없다",
					processes.findAll().stream().map(BusinessProcess::getProcessId).sorted().toList()));
		AiSolution solution = solutions.findById(solutionId)
			.orElseThrow(() -> ApiException.notFound("솔루션 " + solutionId + "는 없다",
					solutions.findAll().stream().map(AiSolution::getSolutionId).sorted().toList()));
		requireSameType(process, solution);
		String exclusionReason = policy.exclusions().get(processId);
		if (exclusionReason != null) {
			throw new ApiException(ErrorCode.EXCLUDED_PROCESS, "업무 " + processId + "는 AX 과제 선정에서 제외된다",
					Map.of("reason", exclusionReason));
		}
		Department department = departments.findById(process.getDepartmentId()).orElseThrow();
		Evaluation evaluation = evaluate(department.getCompanyId(), null);
		CandidateKey key = new CandidateKey(processId, solutionId);
		Optional<InsufficientCandidate> insufficient = evaluation.insufficient()
			.stream()
			.filter(i -> i.key().equals(key))
			.findFirst();
		if (insufficient.isPresent()) {
			throw new ApiException(ErrorCode.INSUFFICIENT_DATA,
					"업무 " + processId + "의 최근 3개 완료 월 지표가 부족하다 (기준일 " + asOf() + ")",
					Map.of("missingMonths", monthStrings(insufficient.get().missingMonths())));
		}
		return evaluation.eligible()
			.stream()
			.filter(c -> c.key().equals(key))
			.findFirst()
			.orElseThrow(() -> ApiException.validation(solution.getDependsOnSolutionId() == null
					? "업무 " + processId + "와 솔루션 " + solutionId + "는 후보가 아니다"
					: "솔루션 " + solutionId + "의 선행 템플릿 " + solution.getDependsOnSolutionId() + "을 붙일 업무가 같은 부서에 없다"));
	}

	/** 업무 유형과 템플릿 유형이 같아야 한다. 유형이 없는 업무는 어떤 템플릿과도 맞지 않는다. */
	private static void requireSameType(BusinessProcess process, AiSolution solution) {
		if (process.getWorkType() != solution.getWorkType()) {
			Map<String, Object> hint = new LinkedHashMap<>();
			hint.put("processWorkType", process.getWorkType());
			hint.put("solutionWorkType", solution.getWorkType());
			throw ApiException.validation("업무 " + process.getProcessId() + "의 유형과 솔루션 " + solution.getSolutionId()
					+ "의 유형이 다르다", hint);
		}
	}

	/**
	 * GET /internal/companies/{id}/solution-candidates. 업무 유형이 같은 (업무, 템플릿) 쌍과 선행 쌍.
	 * 정책상 제외 업무는 excludedReason에, 지표가 부족하면 metricsSufficient=false로 알린다.
	 */
	@Transactional(readOnly = true)
	public List<SolutionCandidate> candidates(Long companyId) {
		CompanyData data = loader.load(companyId);
		CompanyInput input = toInput(data, null);
		Map<Long, BaseValues> bases = baseValues(data);
		return AxCalculator.matches(input, policy)
			.stream()
			.map(m -> new SolutionCandidate(m.processId(), m.solution().solutionId(),
					WorkType.valueOf(m.solution().workType()),
					m.dependsOn() == null ? null : new CandidateRef(m.dependsOn().processId(), m.dependsOn().solutionId()),
					m.excludedReason(), bases.get(m.processId()).sufficient()))
			.toList();
	}

	public record SolutionCandidate(Long processId, Long solutionId, WorkType workType, CandidateRef dependsOn,
			String excludedReason, boolean metricsSufficient) {
	}

	/** 후보 요청 1건 (processId, solutionId). */
	public record CandidateRef(Long processId, Long solutionId) {
	}

	/**
	 * POST /internal/ax/portfolio. candidates가 비어 있거나 null이면 기업의 선정 가능 후보 전체.
	 * 넘어온 후보 중 제외 업무는 선정하지 않고 excluded에 담는다. budgetKrw가 null이면 예산 제약 없이 선정한다.
	 */
	@Transactional(readOnly = true)
	public PortfolioResult portfolio(Long companyId, List<CandidateRef> candidates, Integer maxItems, Long budgetKrw) {
		int size = maxItems == null ? policy.maxItems() : maxItems;
		if (size < 1) {
			throw ApiException.validation("maxItems는 1 이상이어야 한다", Map.of("maxItems", size));
		}
		CompanyData data = loader.load(companyId);
		List<CandidateKey> requested = candidates == null ? List.of() : validate(data, candidates);
		Evaluation evaluation = AxCalculator.evaluate(toInput(data, budgetKrw), policy, asOf());
		return AxCalculator.optimize(evaluation, requested, size);
	}

	private static List<CandidateKey> validate(CompanyData data, Collection<CandidateRef> candidates) {
		Map<Long, AiSolution> templates = data.solutions()
			.stream()
			.collect(Collectors.toMap(AiSolution::getSolutionId, Function.identity()));
		Map<Long, BusinessProcess> companyProcesses = data.processes()
			.stream()
			.collect(Collectors.toMap(BusinessProcess::getProcessId, Function.identity()));
		for (CandidateRef ref : candidates) {
			if (ref.solutionId() == null || ref.processId() == null) {
				throw ApiException.validation("candidates의 processId·solutionId는 필수다");
			}
			AiSolution solution = templates.get(ref.solutionId());
			if (solution == null) {
				throw ApiException.notFound("솔루션 " + ref.solutionId() + "는 없다",
						templates.keySet().stream().sorted().toList());
			}
			BusinessProcess process = companyProcesses.get(ref.processId());
			if (process == null) {
				throw ApiException.notFound("기업 " + data.company().getCompanyId() + "의 업무 " + ref.processId() + "는 없다",
						companyProcesses.keySet().stream().sorted().toList());
			}
			requireSameType(process, solution);
		}
		return candidates.stream().map(r -> new CandidateKey(r.processId(), r.solutionId())).distinct().toList();
	}

	public static List<String> monthStrings(List<YearMonth> months) {
		return months.stream().map(YearMonth::toString).toList();
	}

	static CompanyInput toInput(CompanyData data, Long budgetKrw) {
		Map<Long, Department> depts = data.departmentsById();
		return new CompanyInput(data.company().getCompanyId(), budgetKrw,
				data.processes()
					.stream()
					.filter(p -> depts.containsKey(p.getDepartmentId()))
					.map(p -> new ProcessInput(p.getProcessId(), p.getDepartmentId(), p.getStandardizationLevel(),
							p.isRequiresApproval(), p.getWorkType() == null ? null : p.getWorkType().name()))
					.toList(),
				data.metrics().stream().map(AxCalcService::toMetric).toList(),
				data.systems().stream().map(s -> new SystemInput(s.getSystemId(), s.isApiAvailable())).toList(),
				data.links()
					.stream()
					.map(l -> new LinkInput(l.getId().getProcessId(), l.getId().getSystemId()))
					.toList(),
				data.solutions()
					.stream()
					.map(s -> new SolutionInput(s.getSolutionId(), s.getWorkType().name(), s.getDependsOnSolutionId(),
							s.getBaseBuildCostKrw(), s.getMonthlyRunCostKrw(), s.getAutomationRateLow(),
							s.getAutomationRateBase(), s.getAutomationRateHigh(), s.getTimeReductionRateLow(),
							s.getTimeReductionRateBase(), s.getTimeReductionRateHigh(),
							s.getMinStandardizationLevel(), s.isRequiresApi()))
					.toList());
	}

	private static MetricInput toMetric(ProcessMetric m) {
		return new MetricInput(m.getMetricId(), m.getProcessId(), m.getPeriodStart(), m.getPeriodEnd(),
				m.getProcessingCount(), m.getAvgProcessingMinutes(), m.getErrorRate(), m.getHourlyLaborCostKrw(),
				m.getDataSource().confidence());
	}

}
