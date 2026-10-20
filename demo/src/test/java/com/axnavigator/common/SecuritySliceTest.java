package com.axnavigator.common;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.web.servlet.MockMvc;

import com.axnavigator.auth.JwtConfiguration;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HealthController.class)
@Import({ SecurityConfiguration.class, JwtConfiguration.class })
class SecuritySliceTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JwtEncoder jwtEncoder;

	@Test
	void healthIsPublic() throws Exception {
		mvc.perform(get("/health"))
			.andExpect(status().isOk())
			.andExpect(content().json("{\"status\":\"UP\"}"));
	}

	@Test
	void apiWithoutTokenIs401WithErrorBody() throws Exception {
		mvc.perform(get("/api/anything"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
	}

	@Test
	void apiWithInvalidTokenIs401() throws Exception {
		mvc.perform(get("/api/anything").header("Authorization", "Bearer not-a-jwt"))
			.andExpect(status().isUnauthorized());
	}

	@Test
	void apiWithValidTokenPassesSecurity() throws Exception {
		// 컨트롤러가 없으므로 보안 통과 후 404
		mvc.perform(get("/api/anything").header("Authorization", "Bearer " + token()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("NOT_FOUND"));
	}

	@Test
	void internalWithoutTokenIs403() throws Exception {
		mvc.perform(get("/internal/anything"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("FORBIDDEN"));
	}

	@Test
	void internalWithWrongTokenIs403() throws Exception {
		mvc.perform(get("/internal/anything").header("X-Internal-Token", "wrong"))
			.andExpect(status().isForbidden());
	}

	@Test
	void internalWithTokenPassesSecurity() throws Exception {
		mvc.perform(get("/internal/anything").header("X-Internal-Token", "test-internal-token"))
			.andExpect(status().isNotFound());
	}

	@Test
	void internalDoesNotAcceptUserJwt() throws Exception {
		mvc.perform(get("/internal/anything").header("Authorization", "Bearer " + token()))
			.andExpect(status().isForbidden());
	}

	@Test
	void corsPreflightAllowsConfiguredOrigin() throws Exception {
		mvc.perform(options("/api/ai/tasks").header("Origin", "http://app.example")
			.header("Access-Control-Request-Method", "POST")
			.header("Access-Control-Request-Headers", "Authorization,Content-Type"))
			.andExpect(status().isOk())
			.andExpect(header().string("Access-Control-Allow-Origin", "http://app.example"));
	}

	@Test
	void corsPreflightRejectsOtherOrigin() throws Exception {
		mvc.perform(options("/api/ai/tasks").header("Origin", "http://evil.example")
			.header("Access-Control-Request-Method", "POST"))
			.andExpect(status().isForbidden());
	}

    @Test
    void streamCorsAllowsAuthorizationHeader() throws Exception {
        mvc.perform(options("/api/ai/tasks/example/stream").header("Origin", "http://app.example")
            .header("Access-Control-Request-Method", "GET")
            .header("Access-Control-Request-Headers", "Authorization"))
            .andExpect(status().isOk())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://app.example"));
    }

    @Test
    void asyncAndErrorDispatchAreNotBlockedByAuthentication() throws Exception {
        for (jakarta.servlet.DispatcherType type : new jakarta.servlet.DispatcherType[]{
                jakarta.servlet.DispatcherType.ASYNC, jakarta.servlet.DispatcherType.ERROR}) {
            mvc.perform(get("/api/ai/tasks/example/stream").with(request -> { request.setDispatcherType(type); return request; }))
                .andExpect(status().isNotFound());
        }
    }

	private String token() {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject("601")
			.claim("companyId", 1)
			.claim("name", "tester")
			.issuedAt(Instant.now())
			.expiresAt(Instant.now().plusSeconds(600))
			.build();
		return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
	}

}
