package com.axnavigator.doc;

import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import com.axnavigator.auth.CurrentUser;
import com.axnavigator.doc.DocumentDtos.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;

@RestController
@RequestMapping("/api/documents")
@Tag(name = "문서 업로드", description = "기업 PDF 업로드와 색인 상태 조회")
@SecurityRequirement(name = "BearerAuth")
public class DocumentUploadController {
    private final DocumentUploadService documents;
    public DocumentUploadController(DocumentUploadService documents) { this.documents = documents; }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "PDF 업로드", description = "JWT 기업의 문서로 등록하고 AI 색인을 요청합니다. PDF만 20MB까지, 기업당 20개(색인 실패 제외)까지 가능합니다. 같은 파일은 409 DUPLICATE_DOCUMENT입니다. 색인은 비동기로 진행되며 목록 API로 status가 INDEXED 또는 FAILED가 되는지 확인합니다.")
    public Uploaded upload(@AuthenticationPrincipal Jwt jwt, @RequestPart("file") MultipartFile file) {
        return documents.upload(CurrentUser.from(jwt).companyId(), file);
    }
    @GetMapping
    @Operation(summary = "기업 문서 목록", description = "JWT 기업의 시드·업로드 문서와 색인 상태를 최신순으로 반환합니다.")
    public List<Summary> list(@AuthenticationPrincipal Jwt jwt) { return documents.list(CurrentUser.from(jwt).companyId()); }
}
