package com.axnavigator.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import com.axnavigator.common.SecurityConfiguration;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({AuthService.class, SecurityConfiguration.class, JwtConfiguration.class})
class AuthApiTest {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder passwords;
    @Autowired JwtDecoder decoder;
    @Autowired JwtEncoder encoder;
    @MockitoBean UserAccountRepository users;
    static final JsonMapper JSON = JsonMapper.builder().build();

    @Test void signupHashesPasswordAndReturnsOnlyId() throws Exception {
        when(users.saveAndFlush(any())).thenAnswer(invocation -> {
            UserAccount user = invocation.getArgument(0);
            assertThat(user.getCompanyId()).isEqualTo(1L);
            assertThat(passwords.matches("ChangeMe123!", user.getPasswordHash())).isTrue();
            user.setUserId(603L); return user;
        });
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"new@gaon.example\",\"password\":\"ChangeMe123!\",\"name\":\"홍길동\"}"))
            .andExpect(status().isCreated()).andExpect(content().json("{\"userId\":603}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test void duplicateEmailIs409() throws Exception {
        when(users.existsByEmail("taken@gaon.example")).thenReturn(true);
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"taken@gaon.example\",\"password\":\"ChangeMe123!\",\"name\":\"홍길동\"}"))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }

    @Test void invalidInputsAre400WithoutDatabaseAccess() throws Exception {
        for (String password : new String[]{"short1", "abcdefgh", "12345678", "A1" + "가".repeat(24)}) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("email","new@gaon.example","name","홍길동","password",password))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        for (String body : new String[]{"{}", "{\"email\":\"bad\",\"password\":\"ChangeMe123!\",\"name\":\" \"}", "{"}) {
            mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        verifyNoInteractions(users);
    }

    @Test void seedPasswordsIssueValidTokensAndProtectTaskAndStream() throws Exception {
        String seed = Files.readString(Path.of("../db/init/01_seed_input_postgres.sql"));
        for (long id : new long[]{601L,602L}) {
            String email = "analyst"+(id-600)+"@gaon.example";
            String line = seed.lines().filter(s -> s.startsWith("("+id+",")).findFirst().orElseThrow();
            String hash = line.split("'")[3];
            UserAccount user = new UserAccount();
            user.setUserId(id); user.setCompanyId(1L); user.setEmail(email);
            user.setUserName("분석 담당자"); user.setPasswordHash(hash);
            when(users.findByEmail(email)).thenReturn(Optional.of(user));
            String response = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("email",email,"password","ChangeMe123!"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.userId").value(id))
                .andExpect(jsonPath("$.user.companyId").value(1))
                .andExpect(jsonPath("$.user.passwordHash").doesNotExist()).andReturn().getResponse().getContentAsString();
            String token = JSON.readTree(response).get("accessToken").asString();
            Jwt claims = decoder.decode(token);
            assertThat(claims.getSubject()).isEqualTo(Long.toString(id));
            assertThat(java.time.Duration.between(claims.getIssuedAt(),claims.getExpiresAt())).isEqualTo(java.time.Duration.ofHours(12));
            for (String path : new String[]{"/api/ai/tasks","/api/ai/tasks/example/stream"}) {
                mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
                mvc.perform(get(path).header("Authorization","Bearer "+token)).andExpect(status().isNotFound());
                mvc.perform(get(path).param("token",token)).andExpect(status().isUnauthorized());
                mvc.perform(get(path).param("access_token",token)).andExpect(status().isUnauthorized());
            }
            mvc.perform(get("/internal/companies/1").header("Authorization","Bearer "+token)).andExpect(status().isForbidden());
        }
    }

    @Test void wrongEmailAndPasswordHaveSameResponse() throws Exception {
        UserAccount user = new UserAccount(); user.setPasswordHash(passwords.encode("ChangeMe123!"));
        when(users.findByEmail("known@gaon.example")).thenReturn(Optional.of(user));
        for (String email : new String[]{"known@gaon.example","unknown@gaon.example"}) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(java.util.Map.of("email",email,"password","wrong"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("이메일 또는 비밀번호가 올바르지 않습니다."));
        }
    }

    @Test void expiredTokenIs401() throws Exception {
        Instant past = Instant.now().minusSeconds(3600);
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),
            JwtClaimsSet.builder().subject("601").issuedAt(past).expiresAt(past.plusSeconds(60)).build())).getTokenValue();
        mvc.perform(get("/api/ai/tasks").header("Authorization","Bearer "+token))
            .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }
}
