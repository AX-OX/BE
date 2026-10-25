package com.axnavigator.doc;

import com.axnavigator.recovery.CommandQueue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.axnavigator.common.*;
import com.axnavigator.doc.DocumentDtos.*;

@Service
public class DocumentUploadService {
    public static final long MAX_FILE_BYTES = 20L * 1024 * 1024;
    public static final int MAX_DOCUMENTS = 20;
    public static final int MAX_PAGES = 100;
    private static final byte[] PDF_SIGNATURE = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final Logger log = LoggerFactory.getLogger(DocumentUploadService.class);
    private final CommandQueue commands;
    private final DocumentRepository documents;
    private final DocumentPageRepository pages;
    private final ApplicationEventPublisher events;

    public DocumentUploadService(DocumentRepository documents, DocumentPageRepository pages, ApplicationEventPublisher events, CommandQueue commands) {
        this.commands = commands; this.documents = documents; this.pages = pages; this.events = events;
    }

    @Transactional
    public Uploaded upload(Long companyId, MultipartFile file) {
        if (file == null || file.isEmpty()) throw ApiException.validation("PDF 파일을 첨부해 주세요.");
        if (file.getSize() > MAX_FILE_BYTES) throw ApiException.validation("파일은 20MB 이하여야 합니다.", Map.of("maxBytes", MAX_FILE_BYTES));
        byte[] content = read(file);
        // 확장자·Content-Type은 클라이언트가 바꿀 수 있으므로 파일 시그니처로 PDF를 확인한다.
        if (!Arrays.equals(content, 0, Math.min(content.length, PDF_SIGNATURE.length), PDF_SIGNATURE, 0, PDF_SIGNATURE.length))
            throw ApiException.validation("PDF 파일만 업로드할 수 있습니다.");
        if (documents.countByCompanyIdAndStatusNot(companyId, DocumentStatus.FAILED) >= MAX_DOCUMENTS)
            throw new ApiException(ErrorCode.INVALID_STATE, "기업당 문서는 최대 " + MAX_DOCUMENTS + "개까지 등록할 수 있습니다.", Map.of("maxDocuments", MAX_DOCUMENTS));
        String sha256 = sha256(content);
        // 색인에 실패한 문서는 같은 파일로 다시 올릴 수 있다.
        if (documents.existsByCompanyIdAndSha256AndStatusNot(companyId, sha256, DocumentStatus.FAILED))
            throw new ApiException(ErrorCode.DUPLICATE_DOCUMENT, "이미 업로드한 문서입니다.");
        Document doc = new Document();
        doc.setCompanyId(companyId); doc.setTitle(title(file.getOriginalFilename())); doc.setDocType(DocType.UPLOADED);
        doc.setFileUri("docs://pending"); doc.setAccessLevel(AccessLevel.CONFIDENTIAL); doc.setVersion("v1");
        doc.setStatus(DocumentStatus.UPLOADED); doc.setContentType("application/pdf"); doc.setFileSize((long) content.length); doc.setSha256(sha256);
        documents.saveAndFlush(doc);
        doc.setFileUri("docs://" + doc.getDocumentId());
        // AI 색인 호출은 DB 커밋 이후 비동기로 한다.
        var command = new DocumentSignals.Ingest(doc.getDocumentId(), companyId, doc.getTitle(), doc.getContentType(), content);
        commands.enqueue(command);
        events.publishEvent(command);
        return new Uploaded(doc.getDocumentId(), doc.getStatus());
    }

    @Transactional(readOnly = true)
    public List<Summary> list(Long companyId) {
        return documents.findByCompanyIdOrderByDocumentIdDesc(companyId).stream().map(DocumentUploadService::summary).toList();
    }

    @Transactional
    public Summary applyIndex(Long documentId, IndexResult result) {
        Document doc = lock(documentId);
        if (commands.enabled() && doc.getStatus() == result.status() && doc.getStatus() != DocumentStatus.UPLOADED) return summary(doc);
        if (doc.getStatus() != DocumentStatus.UPLOADED) throw ApiException.invalidState("색인 대기 중인 문서만 결과를 저장할 수 있습니다.");
        List<PageText> received = result.pages() == null ? List.of() : result.pages();
        switch (result.status()) {
            case INDEXED -> {
                if (received.isEmpty() || !Objects.equals(result.pageCount(), received.size()))
                    throw ApiException.validation("INDEXED는 pageCount와 같은 수의 pages가 필요합니다.");
                List<Integer> numbers = received.stream().map(PageText::page).sorted().toList();
                for (int i = 0; i < numbers.size(); i++)
                    if (numbers.get(i) != i + 1) throw ApiException.validation("pages는 1페이지부터 빠짐없이 한 번씩 보내야 합니다.");
                for (PageText text : received) {
                    DocumentPage page = new DocumentPage();
                    page.setDocumentId(documentId); page.setPageNo(text.page()); page.setContent(text.text());
                    pages.save(page);
                }
                pages.flush();
                doc.setPageCount(result.pageCount()); doc.setStatus(DocumentStatus.INDEXED);
            }
            case FAILED -> {
                if (!received.isEmpty()) throw ApiException.validation("FAILED에는 pages를 보낼 수 없습니다.");
                log.warn("Document {} indexing failed: {}", documentId, result.errorMessage());
                doc.setStatus(DocumentStatus.FAILED);
            }
            default -> throw ApiException.validation("status는 INDEXED 또는 FAILED여야 합니다.");
        }
        return summary(doc);
    }

    /** AI 색인 요청 자체가 실패하면 문서를 FAILED로 둔다. 이미 결과가 저장됐으면 그대로 둔다. */
    @Transactional
    public void ingestFailed(Long documentId) {
        Document doc = lock(documentId);
        if (doc.getStatus() == DocumentStatus.UPLOADED) doc.setStatus(DocumentStatus.FAILED);
    }

    private Document lock(Long documentId) {
        return documents.lockById(documentId).orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, "문서를 찾을 수 없습니다."));
    }

    private static Summary summary(Document doc) {
        return new Summary(doc.getDocumentId(), doc.getTitle(), doc.getDocType(), doc.getStatus(), doc.getFileSize(), doc.getPageCount(), doc.getCreatedAt());
    }

    private static byte[] read(MultipartFile file) {
        try { return file.getBytes(); }
        catch (IOException ex) { throw new IllegalStateException("업로드 파일을 읽을 수 없습니다.", ex); }
    }

    static String title(String originalFilename) {
        String name = originalFilename == null ? "" : originalFilename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).strip();
        if (name.isEmpty()) name = "업로드 문서.pdf";
        return name.length() > 200 ? name.substring(0, 200) : name;
    }

    static String sha256(byte[] content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
