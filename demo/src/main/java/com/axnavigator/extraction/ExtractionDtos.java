package com.axnavigator.extraction;

import java.math.BigDecimal;
import java.util.List;
import com.axnavigator.company.DataAccessLevel;
import com.axnavigator.company.DataSourceType;
import com.axnavigator.company.SystemType;
import com.axnavigator.company.WorkType;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

/** 추출 draft와 확정 요청. 확정 전에는 DB ID가 없으므로 key로 부서, 업무, 시스템을 연결한다. */
public final class ExtractionDtos {
    private ExtractionDtos() {}

    public record Payload(
        @Valid CompanyInfo company,
        @NotEmpty @Size(max = 50) List<@Valid @NotNull Department> departments,
        @NotNull @Size(max = 200) List<@Valid @NotNull Process> processes,
        @NotNull @Size(max = 200) List<@Valid @NotNull Metric> metrics,
        @NotNull @Size(max = 100) List<@Valid @NotNull SystemInfo> systems) {}

    public record CompanyInfo(
        @Size(max = 50) @Schema(example = "B2B 정밀부품 제조") String industry,
        @Positive @Schema(example = "200") Integer employeeCount) {}

    public record Department(
        @NotBlank @Size(max = 50) @Schema(example = "d1") String key,
        @NotBlank @Size(max = 50) @Schema(example = "인사팀") String name) {}

    public record Process(
        @NotBlank @Size(max = 50) @Schema(example = "p1") String key,
        @NotBlank @Size(max = 50) @Schema(example = "d1") String departmentKey,
        @NotBlank @Size(max = 100) @Schema(example = "사내 규정 문의 응답") String name,
        String purpose,
        List<@NotBlank String> steps,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 2) @Schema(example = "0.8") BigDecimal standardizationLevel,
        @NotNull Boolean requiresApproval,
        String exceptionRules,
        @NotNull @Schema(description = "업무 유형. 같은 유형의 솔루션 템플릿과 매칭하며 OTHER는 매칭하지 않는다", example = "KNOWLEDGE_QA") WorkType workType,
        @Valid Source source) {}

    /** 월평균 1건. 확정 시 계산 기준일의 최근 3개 완료 월에 같은 값으로 저장한다. */
    public record Metric(
        @NotBlank @Size(max = 50) @Schema(example = "p1") String processKey,
        @NotNull @PositiveOrZero @Schema(description = "월평균 처리 건수", example = "120") Integer monthlyCount,
        @NotNull @Positive @Digits(integer = 6, fraction = 2) @Schema(description = "건당 평균 처리 시간(분)", example = "15") BigDecimal avgMinutes,
        @NotNull @Positive @Schema(description = "시간당 인건비(원)", example = "35000") Integer hourlyLaborCostKrw,
        @DecimalMin("0") @DecimalMax("1") @Digits(integer = 1, fraction = 4) BigDecimal errorRate,
        @NotNull @Schema(description = "EXTRACTED 또는 USER_INPUT", example = "USER_INPUT") DataSourceType dataSource,
        @Valid Source source) {
        @JsonIgnore
        @AssertTrue(message = "dataSource는 EXTRACTED 또는 USER_INPUT이어야 합니다.")
        public boolean isExtractedOrUserInput() {
            return dataSource == null || dataSource == DataSourceType.EXTRACTED || dataSource == DataSourceType.USER_INPUT;
        }
    }

    public record SystemInfo(
        @NotBlank @Size(max = 50) @Schema(example = "s1") String key,
        @NotBlank @Size(max = 100) @Schema(example = "그룹웨어") String name,
        @NotNull SystemType type,
        @NotNull Boolean apiAvailable,
        @NotNull DataAccessLevel dataAccessLevel,
        @NotNull Boolean cloudBased,
        @NotNull List<@NotBlank String> processKeys) {}

    /** 추출 근거. page는 1부터. */
    public record Source(@NotNull @Positive Long documentId, @Positive Integer page) {}

    public record SaveRequest(
        @NotBlank @Schema(example = "a3000000-0000-4000-8000-000000000001") String taskId,
        @NotNull List<@NotNull @Positive Long> documentIds,
        @NotNull @Valid Payload payload) {}

    public record Saved(Long draftId) {}

    public record Draft(Long draftId, DraftStatus status, JsonNode documentIds, JsonNode payload) {}
}
