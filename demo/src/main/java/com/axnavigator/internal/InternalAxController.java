package com.axnavigator.internal;

import java.util.List;

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

/** AX점수·보조 ROI·포트폴리오 계산 (기준 문서 §6·§7). */
@RestController
@RequestMapping("/internal/ax")
public class InternalAxController {

	private final AxCalcService calc;

	public InternalAxController(AxCalcService calc) {
		this.calc = calc;
	}

	public record RoiRequest(@NotNull Long processId, @NotNull Long solutionId) {
	}

	public record PortfolioRequest(@NotNull Long companyId, List<CandidateRef> candidates, Integer maxItems) {
	}

	@PostMapping("/roi")
	public CandidateScore roi(@Valid @RequestBody RoiRequest request) {
		return calc.roi(request.processId(), request.solutionId());
	}

	@PostMapping("/portfolio")
	public PortfolioResult portfolio(@Valid @RequestBody PortfolioRequest request) {
		return calc.portfolio(request.companyId(), request.candidates(), request.maxItems());
	}

}
