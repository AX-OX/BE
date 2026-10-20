package com.axnavigator.common;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration(proxyBeanMethods = false)
public class OpenApiConfiguration {

    @Bean
    OpenAPI axOpenAPI() {
        return new OpenAPI()
            .info(new Info().title("AX Navigator API").version("v1")
                .description("기업 업무 조회, AX 분석 작업·승인·결과 저장 API입니다. 공개 API는 로그인 후 BearerAuth에 accessToken을 입력하세요. 내부 API는 Authorize에서 InternalToken에 서버의 INTERNAL_TOKEN 값을 입력한 뒤 실행하세요. /health는 인증 없이 호출할 수 있습니다. 계산 API는 데이터를 변경하지 않습니다."))
            .components(new Components().addSecuritySchemes("BearerAuth", new SecurityScheme()
                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                .description("로그인 응답의 accessToken을 입력합니다."))
                .addSecuritySchemes("InternalToken", new SecurityScheme()
                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Token")
                .description("서버 환경 변수 INTERNAL_TOKEN 값. Bearer 접두어 없이 입력합니다.")));
    }
}
