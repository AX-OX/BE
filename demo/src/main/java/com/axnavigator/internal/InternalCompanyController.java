package com.axnavigator.internal;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.axnavigator.company.CompanyDtos.BudgetResponse;
import com.axnavigator.company.CompanyDtos.CompanyResponse;
import com.axnavigator.company.CompanyDtos.ProcessMetricsResponse;
import com.axnavigator.company.CompanyDtos.ProcessResponse;
import com.axnavigator.company.CompanyDtos.SolutionResponse;
import com.axnavigator.company.CompanyDtos.SystemResponse;
import com.axnavigator.company.CompanyQueryService;

/**
 * business-mcp용 조회 API (기준 문서 §7). X-Internal-Token 필수.
 * 마스킹은 Agent Guardrail 담당이므로 원본 값(담당자 이메일·연락처 포함)을 그대로 준다.
 */
@RestController
@RequestMapping("/internal")
public class InternalCompanyController {

	private final CompanyQueryService service;

	public InternalCompanyController(CompanyQueryService service) {
		this.service = service;
	}

	@GetMapping("/companies/{companyId}")
	public CompanyResponse company(@PathVariable Long companyId) {
		return service.company(companyId);
	}

	@GetMapping("/companies/{companyId}/processes")
	public List<ProcessResponse> processes(@PathVariable Long companyId,
			@RequestParam(required = false) Long departmentId) {
		return service.processes(companyId, departmentId);
	}

	@GetMapping("/companies/{companyId}/metrics")
	public List<ProcessMetricsResponse> metrics(@PathVariable Long companyId,
			@RequestParam(required = false) Long departmentId) {
		return service.metrics(companyId, departmentId);
	}

	@GetMapping("/companies/{companyId}/systems")
	public List<SystemResponse> systems(@PathVariable Long companyId) {
		return service.systems(companyId);
	}

	@GetMapping("/companies/{companyId}/budget")
	public BudgetResponse budget(@PathVariable Long companyId) {
		return service.budget(companyId);
	}

	@GetMapping("/solutions")
	public List<SolutionResponse> solutions() {
		return service.solutions();
	}

}
