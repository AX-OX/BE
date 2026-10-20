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
            assertThatThrownBy(() -> new DocumentService(repository,properties).read(401L,1L))
                .isInstanceOfSatisfying(ApiException.class,e -> assertThat(e.code()).isEqualTo(ErrorCode.FORBIDDEN));
        } finally { Files.deleteIfExists(outside); }
    }
}
