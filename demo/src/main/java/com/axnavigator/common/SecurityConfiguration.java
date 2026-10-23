package com.axnavigator.common;

import java.util.List;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import com.axnavigator.internal.InternalTokenFilter;

import jakarta.servlet.DispatcherType;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AxProperties.class)
public class SecurityConfiguration {

	/** /internal/** : X-Internal-Token 필수(없거나 틀리면 403), 세션 없음. agent·business-mcp 전용. */
	@Bean
	@Order(1)
	SecurityFilterChain internalSecurityFilterChain(HttpSecurity http, AxProperties properties) throws Exception {
		return http.securityMatcher("/internal/**")
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			// .addFilterBefore(new InternalTokenFilter(properties.internalToken()), AnonymousAuthenticationFilter.class)
			.authorizeHttpRequests(authorize -> authorize
				.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
				.anyRequest().permitAll())
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> JsonErrorWriter.write(response,
						ErrorCode.FORBIDDEN, "내부 토큰이 없거나 올바르지 않다"))
				.accessDeniedHandler((request, response, e) -> JsonErrorWriter.write(response, ErrorCode.FORBIDDEN,
						"내부 토큰이 없거나 올바르지 않다")))
			.build();
	}

	/** /api/** 등 공개 API : JWT Bearer, stateless, csrf off. */
	@Bean
	@Order(2)
	SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
		return http
			.csrf(AbstractHttpConfigurer::disable)
			.httpBasic(AbstractHttpConfigurer::disable)
			.formLogin(AbstractHttpConfigurer::disable)
			.logout(AbstractHttpConfigurer::disable)
			.requestCache(AbstractHttpConfigurer::disable)
			.cors(Customizer.withDefaults())
			.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
			.authorizeHttpRequests(authorize -> authorize
				.dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
				.requestMatchers(HttpMethod.GET, "/health", "/swagger-ui.html", "/swagger-ui/**", "/v3/api-docs", "/v3/api-docs/**").permitAll()
				.requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login").permitAll()
				.anyRequest().permitAll())
			.oauth2ResourceServer(oauth2 -> oauth2
				.jwt(Customizer.withDefaults())
				.authenticationEntryPoint((request, response, e) -> JsonErrorWriter.write(response,
						ErrorCode.UNAUTHORIZED, "로그인이 필요하거나 인증이 만료되었습니다."))
				.accessDeniedHandler((request, response, e) -> JsonErrorWriter.write(response, ErrorCode.FORBIDDEN,
						"접근 권한이 없습니다.")))
			.exceptionHandling(ex -> ex
				.authenticationEntryPoint((request, response, e) -> JsonErrorWriter.write(response,
						ErrorCode.UNAUTHORIZED, "로그인이 필요하거나 인증이 만료되었습니다."))
				.accessDeniedHandler((request, response, e) -> JsonErrorWriter.write(response, ErrorCode.FORBIDDEN,
						"접근 권한이 없습니다.")))
			.build();
	}

	@Bean
	CorsConfigurationSource corsConfigurationSource(AxProperties properties) {
		CorsConfiguration cors = new CorsConfiguration();
		cors.setAllowedOrigins(List.of(properties.cors().origin().split("\\s*,\\s*")));
		cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "OPTIONS"));
		cors.setAllowedHeaders(List.of("Authorization", "Content-Type", "Last-Event-ID"));
		cors.setMaxAge(3600L);
		UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
		source.registerCorsConfiguration("/api/**", cors);
		return source;
	}

	@Bean
	PasswordEncoder passwordEncoder() {
		// $2y$ 해시(시드 계정)도 검증된다. 새 해시는 $2a$로 저장된다.
		return new BCryptPasswordEncoder();
	}

}
