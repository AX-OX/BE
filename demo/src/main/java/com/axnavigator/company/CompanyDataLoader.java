package com.axnavigator.company;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.axnavigator.common.ApiException;

/** 기업 단위 업무 데이터를 읽는다. 없는 기업은 404 NOT_FOUND + hint.validIds. */
@Component
public class CompanyDataLoader {

	private final CompanyRepository companies;

	private final DepartmentRepository departments;

	private final BusinessProcessRepository processes;

	private final ProcessMetricRepository metrics;

	private final ItSystemRepository systems;

	private final ProcessSystemRepository processSystems;

	private final AiSolutionRepository solutions;

	public CompanyDataLoader(CompanyRepository companies, DepartmentRepository departments,
			BusinessProcessRepository processes, ProcessMetricRepository metrics, ItSystemRepository systems,
			ProcessSystemRepository processSystems, AiSolutionRepository solutions) {
		this.companies = companies;
		this.departments = departments;
		this.processes = processes;
		this.metrics = metrics;
		this.systems = systems;
		this.processSystems = processSystems;
		this.solutions = solutions;
	}

	@Transactional(readOnly = true)
	public Company company(Long companyId) {
		return companies.findById(companyId)
			.orElseThrow(() -> ApiException.notFound("기업 " + companyId + "는 없다",
					companies.findAll().stream().map(Company::getCompanyId).sorted().toList()));
	}

	@Transactional(readOnly = true)
	public CompanyData load(Long companyId) {
		Company company = company(companyId);
		List<Department> depts = departments.findByCompanyIdOrderByDepartmentId(companyId);
		List<BusinessProcess> procs = processes
			.findByDepartmentIdInOrderByProcessId(depts.stream().map(Department::getDepartmentId).toList());
		Set<Long> processIds = procs.stream().map(BusinessProcess::getProcessId).collect(Collectors.toSet());
		List<ItSystem> syss = systems.findByCompanyIdOrderBySystemId(companyId);
		List<ProcessSystem> links = processSystems
			.findByIdSystemIdIn(syss.stream().map(ItSystem::getSystemId).toList())
			.stream()
			.filter(l -> processIds.contains(l.getId().getProcessId()))
			.toList();
		return new CompanyData(company, depts, procs,
				metrics.findByProcessIdInOrderByProcessIdAscPeriodStartAsc(processIds), syss, links,
				solutions.findByTargetProcessIdInOrderBySolutionId(processIds));
	}

	/** 기업 소속 부서인지 확인한다. 다른 기업 부서 ID도 404. */
	public static void requireDepartment(CompanyData data, Long departmentId) {
		if (departmentId != null && data.departments().stream().noneMatch(d -> d.getDepartmentId().equals(departmentId))) {
			throw ApiException.notFound("부서 " + departmentId + "는 없다",
					data.departments().stream().map(Department::getDepartmentId).toList());
		}
	}

}
