package com.axnavigator.doc;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.axnavigator.common.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DocumentServiceTest {
    @TempDir Path directory;

    @Test void masksEmailAndCommonKoreanPhoneFormats() {
        String masked=DocumentService.mask("담당자 user+tag@example.co.kr / 010-1234-5678 / 02-123-4567 / +82 10 1234 5678");
        assertThat(masked).isEqualTo("담당자 [이메일 마스킹] / [전화번호 마스킹] / [전화번호 마스킹] / [전화번호 마스킹]");
    }

    @Test void doesNotFollowDocumentSymlinkOutsideConfiguredDirectory() throws Exception {
        Path outside=Files.createTempFile("ax-document-outside", ".md");
        try {
            Files.writeString(outside,"private data");
            Files.createSymbolicLink(directory.resolve("401.md"),outside);
            var repository=mock(DocumentRepository.class);var properties=mock(AxProperties.class);
            when(properties.docs()).thenReturn(new AxProperties.Docs(directory.toString()));
            Document doc=new Document();doc.setDocumentId(401L);doc.setCompanyId(1L);
            when(repository.findById(401L)).thenReturn(java.util.Optional.of(doc));
            assertThatThrownBy(() -> new DocumentService(repository,mock(DocumentPageRepository.class),properties).read(401L,1L))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally { Files.deleteIfExists(outside); }
    }

    @Test void uploadedDocumentReturnsMaskedPagesOnlyAfterIndexing() {
        var repository=mock(DocumentRepository.class);var pages=mock(DocumentPageRepository.class);var properties=mock(AxProperties.class);
        when(properties.docs()).thenReturn(new AxProperties.Docs(directory.toString()));
        Document doc=new Document();doc.setDocumentId(900L);doc.setCompanyId(7L);doc.setTitle("회사 소개.pdf");
        doc.setDocType(DocType.UPLOADED);doc.setStatus(DocumentStatus.UPLOADED);
        when(repository.findById(900L)).thenReturn(java.util.Optional.of(doc));
        var service=new DocumentService(repository,pages,properties);
        assertThatThrownBy(() -> service.read(900L,7L))
            .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_STATE));

        doc.setStatus(DocumentStatus.INDEXED);
        DocumentPage first=new DocumentPage();first.setPageNo(1);first.setContent("문의 ceo@client.example");
        DocumentPage second=new DocumentPage();second.setPageNo(2);second.setContent("연락처 010-1234-5678");
        when(pages.findByDocumentIdOrderByPageNo(900L)).thenReturn(java.util.List.of(first,second));
        var response=service.read(900L,7L);
        assertThat(response.content()).isEqualTo("문의 [이메일 마스킹]\n\n연락처 [전화번호 마스킹]");
        assertThat(response.pages()).extracting(DocumentService.Page::page).containsExactly(1,2);
    }

    @Test void uploadTitleDropsClientPathAndHashIsHex() {
        assertThat(DocumentUploadService.title("C:\\Users\\me\\회사 소개.pdf")).isEqualTo("회사 소개.pdf");
        assertThat(DocumentUploadService.title(" ")).isEqualTo("업로드 문서.pdf");
        assertThat(DocumentUploadService.title("a".repeat(250))).hasSize(200);
        assertThat(DocumentUploadService.sha256("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }
}
