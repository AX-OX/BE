package com.axnavigator.ax;

import java.io.IOException;
import java.io.InputStream;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import com.axnavigator.ax.calc.AxPolicy;

/** 정책 JSON(classpath:ax/config)을 읽는다. 값은 코드에 하드코딩하지 않는다. */
@Configuration(proxyBeanMethods = false)
public class AxPolicyConfiguration {

	static final String AX_SCORE_POLICY = "ax/config/ax_score_policy_v1.json";

	@Bean
	AxPolicy axPolicy() throws IOException {
		try (InputStream in = new ClassPathResource(AX_SCORE_POLICY).getInputStream()) {
			return AxPolicy.read(in);
		}
	}

}
