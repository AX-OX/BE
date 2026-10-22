package com.axnavigator.doc;

import java.time.LocalDateTime;
import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

public final class DocumentDtos {
    private DocumentDtos() {}
    public record Uploaded(Long documentId, DocumentStatus status) {}
    public record Summary(Long documentId, String title, DocType docType, DocumentStatus status,
        Long fileSize, Integer pageCount, LocalDateTime createdAt) {}
    public record PageText(@NotNull @Positive @Schema(example = "1") Integer page,
        @NotNull @Schema(example = "회사 소개 ...") String text) {}
    public record IndexResult(
        @NotNull @Schema(description = "INDEXED 또는 FAILED", example = "INDEXED") DocumentStatus status,
        @Positive @Max(DocumentUploadService.MAX_PAGES) @Schema(example = "2") Integer pageCount,
        @Valid @Schema(description = "INDEXED일 때 1페이지부터 빠짐없이") List<PageText> pages,
        @Size(max = 2000) @Schema(description = "FAILED 사유 (로그에만 기록)") String errorMessage) {}
}
