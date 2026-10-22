package com.axnavigator.internal;

import java.util.List;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import com.axnavigator.common.ErrorResponse;


import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.axnavigator.ax.AxCalcService;
import com.axnavigator.ax.AxCalcService.SolutionCandidate;
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
@Tag(name = "기업·업무 조회", description = "business-mcp가 사용하는 기업 업무 데이터 조회 API")
@SecurityRequirement(name = "InternalToken")
@ApiResponses({
    @ApiResponse(responseCode = "403", description = "내부 토큰 누락 또는 불일치", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "기업·부서가 없거나 다른 기업의 부서인 경우. hint.validIds 제공", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@RestController
@RequestMapping("/internal")
public class InternalCompanyController {

	private final CompanyQueryService service;

	private final AxCalcService calc;

	public InternalCompanyController(CompanyQueryService service, AxCalcService calc) {
		this.service = service;
		this.calc = calc;
	}

	@Operation(summary = "기업 및 부서 조회", description = "기업의 기본 정보와 소속 부서 목록을 반환합니다. 내부 응답의 담당자 이메일과 전화번호는 마스킹하지 않습니다.")
	@GetMapping("/companies/{companyId}")
	public CompanyResponse company(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId) {
		return service.company(companyId);
	}

	@Operation(summary = "업무 프로세스 조회", description = "기업의 업무 절차, 표준화 수준, 승인 필요 여부와 예외 규칙을 조회합니다. departmentId를 생략하면 모든 부서를 조회합니다.")
	@GetMapping("/companies/{companyId}/processes")
	public List<ProcessResponse> processes(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId,
			@Parameter(description = "부서 ID. 생략 시 전체 부서", example = "103") @RequestParam(required = false) Long departmentId) {
		return service.processes(companyId, departmentId);
	}

	@Operation(summary = "최근 3개월 업무 지표 조회", description = "계산 기준일 이전 최근 3개 완료 월의 원본 지표와 집계값을 반환합니다. 한 달이라도 누락되면 집계값은 null이며 missingMonths에 누락 월을 담습니다. 월평균 처리량과 연간 시간은 소수 2자리, 가중 시간당 인건비는 원 단위입니다.")
	@GetMapping("/companies/{companyId}/metrics")
	public List<ProcessMetricsResponse> metrics(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId,
			@Parameter(description = "부서 ID. 생략 시 전체 부서", example = "103") @RequestParam(required = false) Long departmentId) {
		return service.metrics(companyId, departmentId);
	}

	@Operation(summary = "IT 시스템 및 업무 연결 조회", description = "기업의 시스템 목록과 연결된 업무를 조회합니다. API 제공 여부, 데이터 접근 수준, 클라우드 사용 여부를 포함합니다.")
	@GetMapping("/companies/{companyId}/systems")
	public List<SystemResponse> systems(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId) {
		return service.systems(companyId);
	}

	@Operation(summary = "AX 투자 예산 조회", description = "기업의 연간 AX 투자 예산을 원(KRW) 단위로 반환합니다.")
	@GetMapping("/companies/{companyId}/budget")
	public BudgetResponse budget(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId) {
		return service.budget(companyId);
	}

	@Operation(summary = "솔루션 후보 조회", description = "업무 유형(workType)이 같은 업무와 솔루션 템플릿의 쌍을 반환합니다. 선행 템플릿은 같은 부서 업무에 붙이며, 붙일 업무가 없으면 후보에서 뺍니다. 정책상 제외 업무는 excludedReason, 최근 3개 완료 월 지표가 부족하면 metricsSufficient=false입니다.")
	@GetMapping("/companies/{companyId}/solution-candidates")
	public List<SolutionCandidate> solutionCandidates(@Parameter(description = "기업 ID", example = "1") @PathVariable Long companyId) {
		return calc.candidates(companyId);
	}

	@Operation(summary = "AI 솔루션 템플릿 조회", description = "업무 유형별 솔루션 템플릿의 적용 유형, 선행 템플릿, 구축비와 운영비, 시나리오별 자동화율 및 시간 절감률을 반환합니다. 비율은 0~1이며 비용은 원(KRW) 단위입니다.")
	@GetMapping("/solutions")
	public List<SolutionResponse> solutions() {
		return service.solutions();
	}

}
