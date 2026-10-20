package com.axnavigator.company;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.axnavigator.ax.AxCalcService;
import com.axnavigator.ax.calc.AxCalculator;
import com.axnavigator.ax.calc.CalcResults.BaseValues;
import com.axnavigator.company.CompanyDtos.BudgetResponse;
import com.axnavigator.company.CompanyDtos.CompanyResponse;
import com.axnavigator.company.CompanyDtos.DepartmentResponse;
import com.axnavigator.company.CompanyDtos.MetricMonth;
import com.axnavigator.company.CompanyDtos.ProcessMetricsResponse;
import com.axnavigator.company.CompanyDtos.ProcessResponse;
import com.axnavigator.company.CompanyDtos.SolutionResponse;
import com.axnavigator.company.CompanyDtos.SystemProcess;
import com.axnavigator.company.CompanyDtos.SystemResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** business-mcp용 기업·업무 조회. 내부 API이므로 원본 값을 준다(마스킹은 Agent Guardrail 담당). */
@Service
@Transactional(readOnly = true)
public class CompanyQueryService {

	private final CompanyDataLoader loader;

	private final AiSolutionRepository solutions;

	private final AxCalcService calc;

	private final ObjectMapper objectMapper;

	public CompanyQueryService(CompanyDataLoader loader, AiSolutionRepository solutions, AxCalcService calc,
			ObjectMapper objectMapper) {
		this.loader = loader;
		this.solutions = solutions;
		this.calc = calc;
		this.objectMapper = objectMapper;
	}

	public CompanyResponse company(Long companyId) {
		CompanyData data = loader.load(companyId);
		Company c = data.company();
		return new CompanyResponse(c.getCompanyId(), c.getCompanyName(), c.getIndustry(), c.getEmployeeCount(),
				c.getAxBudgetKrw(), data.departments().stream().map(DepartmentResponse::from).toList());
	}

	public List<ProcessResponse> processes(Long companyId, Long departmentId) {
		CompanyData data = loader.load(companyId);
		CompanyDataLoader.requireDepartment(data, departmentId);
		Map<Long, Department> depts = data.departmentsById();
		return data.processes()
			.stream()
			.filter(p -> departmentId == null || p.getDepartmentId().equals(departmentId))
			.map(p -> new ProcessResponse(p.getProcessId(), p.getDepartmentId(),
					depts.get(p.getDepartmentId()).getDepartmentName(), p.getProcessName(), p.getPurpose(),
					parse(p.getProcessSteps()), p.getStandardizationLevel(), p.isRequiresApproval(),
					p.getExceptionRules()))
			.toList();
	}

	public List<ProcessMetricsResponse> metrics(Long companyId, Long departmentId) {
		CompanyData data = loader.load(companyId);
		CompanyDataLoader.requireDepartment(data, departmentId);
		Map<Long, BaseValues> bases = calc.baseValues(data);
		Map<Long, ProcessMetric> metricsById = data.metrics()
			.stream()
			.collect(Collectors.toMap(ProcessMetric::getMetricId, m -> m));
		return data.processes()
			.stream()
			.filter(p -> departmentId == null || p.getDepartmentId().equals(departmentId))
			.map(p -> {
				BaseValues base = bases.get(p.getProcessId());
				List<MetricMonth> months = base.months()
					.stream()
					.map(m -> metricsById.get(m.metricId()))
					.sorted(Comparator.comparing(ProcessMetric::getPeriodStart))
					.map(m -> new MetricMonth(m.getMetricId(), m.getPeriodStart(), m.getPeriodEnd(),
							m.getProcessingCount(), m.getAvgProcessingMinutes(), m.getErrorRate(),
							m.getHourlyLaborCostKrw(), m.getDataSource().name()))
					.toList();
				boolean ok = base.sufficient();
				return new ProcessMetricsResponse(p.getProcessId(), p.getProcessName(), p.getDepartmentId(), months,
						ok ? AxCalculator.round(base.monthlyAvgCount(), 2) : null,
						ok ? AxCalculator.round(base.annualHours(), 2) : null,
						ok ? AxCalculator.money(base.weightedHourlyLaborCost()) : null, base.dataConfidence(),
						AxCalcService.monthStrings(base.missingMonths()));
			})
			.toList();
	}

	public List<SystemResponse> systems(Long companyId) {
		CompanyData data = loader.load(companyId);
		Map<Long, List<ProcessSystem>> linksBySystem = data.links()
			.stream()
			.collect(Collectors.groupingBy(l -> l.getId().getSystemId()));
		return data.systems()
			.stream()
			.map(s -> new SystemResponse(s.getSystemId(), s.getSystemName(), s.getSystemType(), s.isApiAvailable(),
					s.getDataAccessLevel(), s.isCloudBased(),
					linksBySystem.getOrDefault(s.getSystemId(), List.of())
						.stream()
						.sorted(Comparator.comparing(l -> l.getId().getProcessId()))
						.map(l -> new SystemProcess(l.getId().getProcessId(), l.getUsagePurpose()))
						.toList()))
			.toList();
	}

	public BudgetResponse budget(Long companyId) {
		Company c = loader.company(companyId);
		return new BudgetResponse(c.getCompanyId(), c.getAxBudgetKrw());
	}

	public List<SolutionResponse> solutions() {
		return solutions.findAll()
			.stream()
			.sorted(Comparator.comparing(AiSolution::getSolutionId))
			.map(SolutionResponse::from)
			.toList();
	}

	private JsonNode parse(String json) {
		return json == null ? null : objectMapper.readTree(json);
	}

}
