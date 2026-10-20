package com.axnavigator.ax;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

public final class StorageDtos {
    private StorageDtos() {}
    public record AssessmentRequest(@NotBlank String taskId,
        @NotNull @Schema(description = "5개 영역: data, infra, automation, aiCapability, governance. 각각 0~100") Map<String, BigDecimal> scores,
        @NotBlank @Size(max = 20) String criteriaVersion, @NotNull JsonNode evidence) {}
    public record AssessmentResponse(Long assessmentId) {}
    public record RoadmapRequest(@NotBlank String taskId, @NotNull Long companyId, Long assessmentId,
        @NotBlank @Size(max = 200) String roadmapName, @NotEmpty List<@NotNull @Valid ItemRequest> items) {}
    public record ItemRequest(@NotNull Long processId, @NotNull Long solutionId,
        @NotBlank @Size(max = 200) String itemName, @NotNull @Min(1) Integer priorityOrder,
        @NotNull Phase phase, @NotNull @Min(1) Integer startMonth, @NotNull @Min(1) Integer durationMonths,
        @NotNull @PositiveOrZero Long allocatedBudgetKrw, @NotNull BigDecimal axScore,
        @NotNull BigDecimal impactScore, @NotNull BigDecimal feasibilityScore,
        @NotNull Long expectedAnnualSavingKrw, @NotNull BigDecimal roiPct, BigDecimal paybackMonths,
        @NotNull Long threeYearNetValueKrw, @NotBlank String rationale, @NotNull JsonNode evidence) {}
    public record RoadmapResponse(Long roadmapId, List<Long> itemIds) {}
    public record ProjectRequest(@NotNull Long itemId, @NotNull Long ownerDepartmentId,
        @NotBlank @Size(max = 200) String projectName) {}
    public record ProjectResponse(Long projectId) {}
    public record StatusRequest(@NotNull ProjectStatus status) {}
    public record StatusResponse(Long projectId, ProjectStatus projectStatus) {}
}
