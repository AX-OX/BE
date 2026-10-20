package com.axnavigator.internal;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.axnavigator.auth.JwtConfiguration;
import com.axnavigator.ax.AxCalcService;
import com.axnavigator.common.ApiException;
import com.axnavigator.common.ErrorCode;
import com.axnavigator.common.SecurityConfiguration;
import com.axnavigator.company.CompanyDtos.BudgetResponse;
import com.axnavigator.company.CompanyDtos.CompanyResponse;
import com.axnavigator.company.CompanyDtos.DepartmentResponse;
import com.axnavigator.company.CompanyQueryService;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ InternalCompanyController.class, InternalAxController.class })
@Import({ SecurityConfiguration.class, JwtConfiguration.class })
class InternalApiSliceTest {

	static final String TOKEN = "test-internal-token";

	@Autowired
	MockMvc mvc;

	@MockitoBean
	CompanyQueryService companies;

	@MockitoBean
	AxCalcService calc;

	@Disabled("개발 중 permitAll 및 내부 토큰 필터 비활성화. 인증 복구 시 이 테스트도 다시 활성화한다.")
	@Test
	void withoutTokenIs403() throws Exception {
		mvc.perform(get("/internal/companies/1"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
		mvc.perform(post("/internal/ax/roi").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void companyReturnsRawManagerContact() throws Exception {
		given(companies.company(1L)).willReturn(new CompanyResponse(1L, "가온", "제조", 200, 300_000_000L,
				List.of(new DepartmentResponse(101L, "인사", "가상담당자A", "demo.101@gaon.example", "010-0000-0001"))));

		mvc.perform(get("/internal/companies/1").header(InternalTokenFilter.HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(1))
			.andExpect(jsonPath("$.axBudgetKrw").value(300_000_000))
			.andExpect(jsonPath("$.departments[0].departmentId").value(101))
			.andExpect(jsonPath("$.departments[0].managerEmail").value("demo.101@gaon.example"))
			.andExpect(jsonPath("$.departments[0].managerPhone").value("010-0000-0001"));
	}

	@Test
	void unknownCompanyIs404WithValidIds() throws Exception {
		given(companies.budget(99L)).willThrow(ApiException.notFound("기업 99는 없다", List.of(1L)));

		mvc.perform(get("/internal/companies/99/budget").header(InternalTokenFilter.HEADER, TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"))
			.andExpect(jsonPath("$.message").value("기업 99는 없다"))
			.andExpect(jsonPath("$.hint.validIds[0]").value(1));
	}

	@Test
	void unknownDepartmentIs404WithValidIds() throws Exception {
		given(companies.processes(1L, 99L))
			.willThrow(ApiException.notFound("부서 99는 없다", List.of(101L, 102L, 103L)));

		mvc.perform(get("/internal/companies/1/processes").param("departmentId", "99")
			.header(InternalTokenFilter.HEADER, TOKEN))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.hint.validIds.length()").value(3));
	}

	@Test
	void budget() throws Exception {
		given(companies.budget(1L)).willReturn(new BudgetResponse(1L, 300_000_000L));

		mvc.perform(get("/internal/companies/1/budget").header(InternalTokenFilter.HEADER, TOKEN))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.companyId").value(1))
			.andExpect(jsonPath("$.axBudgetKrw").value(300_000_000));
	}

	@Test
	void roiRequiresIds() throws Exception {
		mvc.perform(post("/internal/ax/roi").header(InternalTokenFilter.HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"processId\":201}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
			.andExpect(jsonPath("$.hint.fields.solutionId").exists());
	}

	@Test
	void roiErrorsKeepHint() throws Exception {
		given(calc.roi(203L, 503L)).willThrow(new ApiException(ErrorCode.EXCLUDED_PROCESS, "제외",
				Map.of("reason", "사유")));
		given(calc.roi(eq(201L), eq(502L))).willThrow(ApiException.validation("불일치",
				Map.of("expectedProcessId", 202L)));

		mvc.perform(post("/internal/ax/roi").header(InternalTokenFilter.HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"processId\":203,\"solutionId\":503}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("EXCLUDED_PROCESS"))
			.andExpect(jsonPath("$.hint.reason").value("사유"));
		mvc.perform(post("/internal/ax/roi").header(InternalTokenFilter.HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"processId\":201,\"solutionId\":502}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.hint.expectedProcessId").value(202));
	}

	@Test
	void malformedJsonIs400() throws Exception {
		mvc.perform(post("/internal/ax/portfolio").header(InternalTokenFilter.HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{not json"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
	}

	@Test
	void portfolioRequiresCompanyId() throws Exception {
		mvc.perform(post("/internal/ax/portfolio").header(InternalTokenFilter.HEADER, TOKEN)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"candidates\":[]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.hint.fields.companyId").exists());
	}


}
