package com.axnavigator.doc;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import com.axnavigator.auth.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/docs")
@Tag(name = "근거 문서", description = "기업 소속 문서의 마스킹된 원문 제공")
@SecurityRequirement(name = "BearerAuth")
public class DocumentController {
    private final DocumentService documents;
    public DocumentController(DocumentService documents) { this.documents = documents; }
    @GetMapping("/{documentId}")
    @Operation(summary = "근거 문서 원문 조회", description = "JWT와 같은 기업의 문서만 조회합니다. 마크다운 원문의 이메일·전화번호를 마스킹합니다. 다른 기업 문서는 403, 없는 문서·원문은 404입니다.")
    public DocumentService.Response read(@AuthenticationPrincipal Jwt jwt, @PathVariable Long documentId) {
        return documents.read(documentId, CurrentUser.from(jwt).companyId());
    }
}
