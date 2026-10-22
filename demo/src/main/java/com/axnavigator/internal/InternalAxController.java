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


import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.axnavigator.ax.AxCalcService;
import com.axnavigator.ax.AxCalcService.CandidateRef;
import com.axnavigator.ax.calc.CalcResults.CandidateScore;
import com.axnavigator.ax.calc.CalcResults.PortfolioResult;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** AX점수·보조 ROI·포트폴리오 계산 (기준 문서 §6·§7). */
@Tag(name = "AX 투자 분석", description = "정책과 업무 지표에 따른 AX점수·ROI 계산 및 예산 내 과제 선정")
@SecurityRequirement(name = "InternalToken")
@ApiResponses({
    @ApiResponse(responseCode = "400", description = "입력 오류, 업무와 솔루션 유형 불일치, 제외 업무 또는 지표 부족. code와 hint로 상세 원인 확인", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "403", description = "내부 토큰 누락 또는 불일치", content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
    @ApiResponse(responseCode = "404", description = "기업·업무·솔루션을 찾을 수 없음", content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
})
@RestController
@RequestMapping("/internal/ax")
public class InternalAxController {

	private final AxCalcService calc;

	public InternalAxController(AxCalcService calc) {
		this.calc = calc;
	}

	public record RoiRequest(@Schema(description = "업무 ID", example = "207") @NotNull Long processId, @Schema(description = "업무와 유형이 같은 솔루션 템플릿 ID", example = "507") @NotNull Long solutionId) {
	}

	public record PortfolioRequest(@Schema(description = "기업 ID", example = "1") @NotNull Long companyId, @Schema(description = "검토할 후보. 생략하거나 빈 배열이면 기업의 선정 가능 후보 전체") List<CandidateRef> candidates, @Schema(description = "선정할 과제 수. 기본 3, 1 이상", example = "3") Integer maxItems, @Schema(description = "예산(원). 생략하면 예산 제약 없이 선정", example = "300000000") @Positive Long budgetKrw) {
	}

	@Operation(summary = "후보 과제 AX점수·ROI 계산", description = "업무와 솔루션 한 쌍의 AX점수, Impact, Feasibility와 보수·기준·낙관 시나리오별 ROI·회수 기간·3년 순편익을 계산합니다. AX점수 정규화는 기업의 선정 가능 후보 전체를 기준으로 합니다. 금액은 KRW, ROI는 %, 회수 기간은 개월입니다. 회수 불가 시 paybackMonths는 null입니다. 절감액은 인력 시간 환산 가치이며 확정 현금 절감액이 아닙니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
        content = @Content(schema = @Schema(implementation = RoiRequest.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "{\"processId\":207,\"solutionId\":507}")))
    @PostMapping("/roi")
	public CandidateScore roi(@Valid @RequestBody RoiRequest request) {
		return calc.roi(request.processId(), request.solutionId());
	}

	@Operation(summary = "최적 AX 과제 조합 선정", description = "예산(요청 시), 업무 중복 금지, 선행 과제 조건을 만족하는 조합을 전수 탐색해 AX점수 합이 최대인 조합을 선정합니다. 동점은 3년 순편익, 낮은 총비용 순으로 결정합니다. ROI는 선정 기준이 아닌 보조 지표입니다. 지표 부족 후보는 warnings, 정책상 제외 업무는 excluded에 반환합니다. 조합이 없으면 HTTP 200과 insufficient=true, selected=[]를 반환합니다. runnerUp은 2등 조합이며 greedyTop3는 단순 상위 선택의 제약 위반을 설명합니다.")
    @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true,
        content = @Content(schema = @Schema(implementation = PortfolioRequest.class),
            examples = @io.swagger.v3.oas.annotations.media.ExampleObject(value = "{\"companyId\":1,\"maxItems\":3,\"budgetKrw\":300000000}")))
    @PostMapping("/portfolio")
	public PortfolioResult portfolio(@Valid @RequestBody PortfolioRequest request) {
		return calc.portfolio(request.companyId(), request.candidates(), request.maxItems(), request.budgetKrw());
	}

}
