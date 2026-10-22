package com.axnavigator.auth;

import com.axnavigator.company.CompanyRepository;
import com.axnavigator.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@IntegrationTest
@AutoConfigureMockMvc
@Transactional
class AuthIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserAccountRepository users;
    @Autowired CompanyRepository companies;
    @Autowired PasswordEncoder passwords;

    @Test void bothSeedAccountsLoginAgainstPostgres() throws Exception {
        for (long id : new long[]{601L,602L}) {
            String email = users.findById(id).orElseThrow().getEmail();
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\""+email+"\",\"password\":\"ChangeMe123!\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.user.userId").value(id))
                .andExpect(jsonPath("$.accessToken").isNotEmpty());
        }
    }

    @Test void signupPersistsHashAndCanLoginAndRejectsDuplicate() throws Exception {
        String body = "{\"email\":\"integration@gaon.example\",\"password\":\"ChangeMe123!\",\"name\":\"통합 테스트\"}";
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isCreated()).andExpect(jsonPath("$.userId").isNumber());
        UserAccount user = users.findByEmail("integration@gaon.example").orElseThrow();
        assertThat(user.getCompanyId()).isNotEqualTo(1L);
        assertThat(companies.findById(user.getCompanyId()).orElseThrow().getCompanyName()).isEqualTo("통합 테스트");
        assertThat(passwords.matches("ChangeMe123!",user.getPasswordHash())).isTrue();
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
            .content("{\"email\":\"integration@gaon.example\",\"password\":\"ChangeMe123!\"}"))
            .andExpect(status().isOk());
        mvc.perform(post("/api/auth/signup").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DUPLICATE_EMAIL"));
    }
}
