package com.axnavigator.doc;

public final class DocumentSignals {
    private DocumentSignals() {}
    /** 커밋 이후 AI 색인 API로 보낼 업로드 파일. 원본은 서버에 저장하지 않는다. */
    public record Ingest(Long documentId, Long companyId, String fileName, String contentType, byte[] content) {}
}
