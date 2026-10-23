package com.axnavigator.doc;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.common.*;

@Service
public class DocumentService {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<![0-9])(?:\\+82[- .]?)?0?(?:1[016789]|2|[3-6][1-5]|70)[- .]?\\d{3,4}[- .]?\\d{4}(?![0-9])");
    private final DocumentRepository documents;
    private final DocumentPageRepository pages;
    private final Path directory;
    public DocumentService(DocumentRepository documents, DocumentPageRepository pages, AxProperties properties) {
        this.documents = documents; this.pages = pages; this.directory = Path.of(properties.docs().dir()).toAbsolutePath().normalize();
    }
    /** pages는 업로드 문서만 채우고 시드 마크다운 문서는 null이다. */
    public record Response(Long documentId, String title, DocType docType, AccessLevel accessLevel, String version, String content, List<Page> pages) {}
    public record Page(Integer page, String text) {}
    @Transactional(readOnly = true)
    public Response read(Long documentId, Long companyId) {
        Document doc = documents.findById(documentId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,"문서를 찾을 수 없습니다."));
        if (!doc.getCompanyId().equals(companyId)) throw ApiException.forbidden("소속 기업의 문서만 조회할 수 있습니다.");
        if (doc.getDocType() == DocType.UPLOADED) return uploaded(doc);
        // DB의 fileUri를 파일 경로로 사용하지 않아 경로 탈출·임의 파일 조회를 막는다.
        Path file = directory.resolve(documentId + ".md");
        try {
            if (!file.toRealPath().startsWith(directory.toRealPath())) throw ApiException.forbidden("허용되지 않은 문서 경로입니다.");
            String content = mask(Files.readString(file));
            return new Response(documentId, mask(doc.getTitle()), doc.getDocType(), doc.getAccessLevel(), doc.getVersion(), content, null);
        } catch (NoSuchFileException ex) { throw new ApiException(ErrorCode.NOT_FOUND,"문서 원문을 찾을 수 없습니다."); }
        catch (IOException ex) { throw new IllegalStateException("문서 원문을 읽을 수 없습니다.", ex); }
    }
    private Response uploaded(Document doc) {
        if (doc.getStatus() != DocumentStatus.INDEXED) throw ApiException.invalidState("문서 색인이 끝나지 않았습니다. 상태: " + doc.getStatus());
        List<Page> texts = pages.findByDocumentIdOrderByPageNo(doc.getDocumentId()).stream().map(p -> new Page(p.getPageNo(), mask(p.getContent()))).toList();
        String content = String.join("\n\n", texts.stream().map(Page::text).toList());
        return new Response(doc.getDocumentId(), mask(doc.getTitle()), doc.getDocType(), doc.getAccessLevel(), doc.getVersion(), content, texts);
    }
    static String mask(String value) { return PHONE.matcher(EMAIL.matcher(value).replaceAll("[이메일 마스킹]")).replaceAll("[전화번호 마스킹]"); }
}
