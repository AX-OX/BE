package com.axnavigator.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import com.axnavigator.common.ErrorCode;
import com.axnavigator.common.JsonErrorWriter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /internal/** 전용. X-Internal-Token이 없거나 다르면 403. 비교는 상수 시간(MessageDigest.isEqual).
 * 서블릿 필터로 자동 등록되지 않도록 빈으로 만들지 않고 SecurityConfiguration에서 직접 생성한다.
 */
public class InternalTokenFilter extends OncePerRequestFilter {

	public static final String HEADER = "X-Internal-Token";

	private final byte[] expected;

	public InternalTokenFilter(String internalToken) {
		this.expected = internalToken.getBytes(StandardCharsets.UTF_8);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String token = request.getHeader(HEADER);
		if (token == null || !MessageDigest.isEqual(expected, token.getBytes(StandardCharsets.UTF_8))) {
			JsonErrorWriter.write(response, ErrorCode.FORBIDDEN, "내부 토큰이 없거나 올바르지 않다");
			return;
		}
		var authentication = UsernamePasswordAuthenticationToken.authenticated("internal", null,
				List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
		SecurityContextHolder.getContext().setAuthentication(authentication);
		chain.doFilter(request, response);
	}

}
