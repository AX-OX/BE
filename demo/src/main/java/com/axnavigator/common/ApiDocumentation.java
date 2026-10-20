package com.axnavigator.common;

import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.responses.ApiResponse;
import java.util.Map;

/** 전체 API의 인증·검증 오류 응답을 같은 형태로 문서화한다. */
@Configuration(proxyBeanMethods = false)
public class ApiDocumentation {
    @Bean
    OpenApiCustomizer workflowResponses() {
        return api -> api.getPaths().forEach((path, item) -> {
            if (!path.startsWith("/api/ai/") && !path.startsWith("/api/docs/")
                    && !path.startsWith("/internal/tasks/") && !path.equals("/internal/assessments")
                    && !path.equals("/internal/roadmaps") && !path.startsWith("/internal/projects")) return;
            item.readOperations().forEach(operation -> {
                Map<String,String> errors = Map.of("400","VALIDATION_ERROR: 요청 형식·입력값 오류. 계산 불일치는 CALCULATION_MISMATCH와 hint.expected 반환",
                    path.startsWith("/internal/") ? "403" : "401", path.startsWith("/internal/") ? "FORBIDDEN: 내부 토큰 누락 또는 불일치" : "UNAUTHORIZED: JWT 누락·만료·불일치",
                    "404","NOT_FOUND: 대상 작업·문서·등록 항목 없음", "409","INVALID_STATE: 현재 상태에서 허용되지 않는 작업");
                errors.forEach((status,description) -> operation.getResponses().putIfAbsent(status, error(description)));
                if (path.startsWith("/api/")) operation.getResponses().putIfAbsent("403",error("FORBIDDEN: 다른 사용자의 작업 또는 다른 기업의 문서"));
            });
        });
    }
    private static ApiResponse error(String description) {
        Schema<Object> schema = new Schema<>().type("object")
            .addProperty("code", new Schema<>().type("string"))
            .addProperty("message", new Schema<>().type("string"))
            .addProperty("hint", new Schema<>().type("object"));
        return new ApiResponse().description(description).content(new Content().addMediaType("application/json",new MediaType().schema(schema)));
    }
}
