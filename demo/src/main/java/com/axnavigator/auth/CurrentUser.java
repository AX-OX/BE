package com.axnavigator.auth;

import org.springframework.security.oauth2.jwt.Jwt;
import com.axnavigator.common.*;

public record CurrentUser(Long userId, Long companyId) {
    public static CurrentUser from(Jwt jwt) {
        try {
            long user = Long.parseLong(jwt.getSubject());
            long company = ((Number)jwt.getClaim("companyId")).longValue();
            if (user <= 0 || company <= 0) throw new IllegalArgumentException();
            return new CurrentUser(user, company);
        } catch (RuntimeException ex) { throw new ApiException(ErrorCode.UNAUTHORIZED, "사용자 인증 정보가 올바르지 않습니다. 다시 로그인해 주세요."); }
    }
}
