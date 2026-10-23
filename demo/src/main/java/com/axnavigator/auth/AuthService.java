package com.axnavigator.auth;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.auth.AuthDtos.*;
import com.axnavigator.common.*;
import com.axnavigator.company.Company;
import com.axnavigator.company.CompanyRepository;

@Service
public class AuthService {
    private final UserAccountRepository users;
    private final CompanyRepository companies;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwt;
    private final AxProperties properties;
    private final String dummyHash;

    public AuthService(UserAccountRepository users, CompanyRepository companies, PasswordEncoder passwords, JwtEncoder jwt, AxProperties properties) {
        this.users = users;
        this.companies = companies;
        this.passwords = passwords;
        this.jwt = jwt;
        this.properties = properties;
        this.dummyHash = passwords.encode("dummy-password-for-login-check");
    }

    @Transactional
    public SignupResponse signup(SignupRequest request) {
        if (users.existsByEmail(request.email())) throw duplicateEmail();
        // 사용자 = 고객사. 가입 이름을 회사명으로 쓰고 나머지 회사 정보는 문서 추출 확정 시 채운다.
        Company company = new Company();
        company.setCompanyName(request.name());
        UserAccount user = new UserAccount();
        user.setCompanyId(companies.saveAndFlush(company).getCompanyId());
        user.setEmail(request.email());
        user.setUserName(request.name());
        user.setPasswordHash(passwords.encode(request.password()));
        try {
            return new SignupResponse(users.saveAndFlush(user).getUserId());
        } catch (DataIntegrityViolationException ex) {
            // 사전 중복 검사 이후 동시 가입한 경우에도 이메일 유니크 제약을 409로 변환한다.
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.hibernate.exception.ConstraintViolationException violation
                    && "uq_users_email".equals(violation.getConstraintName())) throw duplicateEmail();
            }
            throw ex;
        }
    }

    @Transactional(readOnly = true)
    public LoginResponse login(LoginRequest request) {
        UserAccount user = users.findByEmail(request.email()).orElse(null);
        boolean validLength = request.password().getBytes(StandardCharsets.UTF_8).length <= 72;
        boolean matches = passwords.matches(validLength ? request.password() : "invalid-password", user == null ? dummyHash : user.getPasswordHash());
        if (user == null || !validLength || !matches) {
            throw new ApiException(ErrorCode.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().subject(user.getUserId().toString())
            .claim("companyId", user.getCompanyId()).claim("name", user.getUserName())
            .issuedAt(now).expiresAt(now.plus(properties.jwt().ttl())).build();
        String token = jwt.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        return new LoginResponse(token, new UserResponse(user.getUserId(), user.getUserName(), user.getCompanyId()));
    }

    private static ApiException duplicateEmail() {
        return new ApiException(ErrorCode.DUPLICATE_EMAIL, "이미 사용 중인 이메일입니다.");
    }
}
