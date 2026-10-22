package com.axnavigator.internal;

import org.springframework.web.bind.annotation.*;
import com.axnavigator.doc.DocumentUploadService;
import com.axnavigator.doc.DocumentDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/internal/documents")
@Tag(name = "문서 색인 콜백", description = "AI 색인 결과 저장")
@SecurityRequirement(name = "InternalToken")
public class InternalDocumentController {
    private final DocumentUploadService documents;
    public InternalDocumentController(DocumentUploadService documents) { this.documents = documents; }
    @PatchMapping("/{documentId}")
    @Operation(summary = "문서 색인 결과 저장", description = "UPLOADED 문서만 INDEXED 또는 FAILED로 바꿉니다. INDEXED는 pageCount와 1페이지부터 빠짐없는 pages가 필요하며 최대 100페이지입니다. 이미 결과가 저장된 문서는 409입니다.")
    public Summary update(@PathVariable Long documentId, @Valid @RequestBody IndexResult request) { return documents.applyIndex(documentId, request); }
}
