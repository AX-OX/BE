package com.axnavigator.doc;

import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import com.axnavigator.common.JsonSupport;
import com.axnavigator.company.Company;
import com.axnavigator.company.CompanyRepository;
import com.axnavigator.support.IntegrationTest;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** 실제 PostgreSQL + HTTP mock Agent로 PDF 업로드, 색인 요청, 색인 결과 저장, 원문 조회를 검증한다. */
@IntegrationTest
class DocumentIntegrationTest {
    record Call(String path, String body) {}
    static final BlockingQueue<Call> CALLS = new LinkedBlockingQueue<>();
    static final HttpServer AGENT;
    static {
        try {
            AGENT = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            AGENT.createContext("/documents/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1);
                CALLS.add(new Call(exchange.getRequestURI().getPath(),exchange.getRequestHeaders().getFirst("Content-Type")+"\n"+body));
                exchange.sendResponseHeaders(body.contains("simulate-ingest-failure") ? 503 : 202,-1); exchange.close();
            });
            AGENT.start();
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource
    static void agentProperties(DynamicPropertyRegistry properties) {
        properties.add("ax.agent.enabled", () -> true);
        properties.add("ax.agent.base-url", () -> "http://127.0.0.1:"+AGENT.getAddress().getPort());
    }
    @AfterAll static void stopAgent() { AGENT.stop(0); }
    @LocalServerPort int port;
    @Autowired JwtEncoder encoder;
    @Autowired JsonSupport json;
    @Autowired CompanyRepository companies;
    @Autowired DocumentRepository documents;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void uploadIngestIndexAndReadFlow() throws Exception {
        long company = company();
        var uploaded = upload(company, "회사 소개.pdf", pdf("intro"));
        assertThat(uploaded.statusCode()).withFailMessage(uploaded.body()).isEqualTo(201);
        long id = body(uploaded).path("documentId").asLong();
        assertThat(body(uploaded).path("status").asString()).isEqualTo("UPLOADED");

        Call call = awaitCall(id);
        assertThat(call.body()).startsWith("multipart/form-data; boundary=")
            .contains("name=\"companyId\"\r\n\r\n"+company+"\r\n").contains("%PDF-1.4 intro");
        Document stored = documents.findById(id).orElseThrow();
        assertThat(stored.getDocType()).isEqualTo(DocType.UPLOADED);
        assertThat(stored.getFileUri()).isEqualTo("docs://"+id);
        assertThat(stored.getSha256()).hasSize(64);

        assertThat(read(company,id).statusCode()).isEqualTo(409);
        assertThat(index(id,Map.of("status","INDEXED","pageCount",2,"pages",List.of(Map.of("page",1,"text","a")))).statusCode()).isEqualTo(400);
        assertThat(index(id,Map.of("status","INDEXED","pageCount",2,"pages",List.of(Map.of("page",1,"text","a"),Map.of("page",3,"text","b")))).statusCode()).isEqualTo(400);
        var indexed = index(id,Map.of("status","INDEXED","pageCount",2,"pages",List.of(
            Map.of("page",2,"text","연락처 010-1234-5678"),Map.of("page",1,"text","가온 소개 ceo@client.example"))));
        assertThat(indexed.statusCode()).withFailMessage(indexed.body()).isEqualTo(200);
        assertThat(body(indexed).path("status").asString()).isEqualTo("INDEXED");
        assertThat(index(id,Map.of("status","FAILED")).statusCode()).isEqualTo(409);

        JsonNode list = body(request("GET","/api/documents",null,token(company)));
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("pageCount").asInt()).isEqualTo(2);
        assertThat(list.get(0).path("title").asString()).isEqualTo("회사 소개.pdf");

        JsonNode doc = body(read(company,id));
        assertThat(doc.path("content").asString()).isEqualTo("가온 소개 [이메일 마스킹]\n\n연락처 [전화번호 마스킹]");
        assertThat(doc.path("pages").get(1).path("page").asInt()).isEqualTo(2);
        assertThat(read(company(),id).statusCode()).isEqualTo(403);
    }

    @Test void rejectsNonPdfDuplicateAndOverLimit() throws Exception {
        long company = company();
        assertThat(upload(company,"memo.pdf","not a pdf".getBytes(StandardCharsets.UTF_8)).statusCode()).isEqualTo(400);
        assertThat(upload(company,"empty.pdf",new byte[0]).statusCode()).isEqualTo(400);
        assertThat(upload(company,"big.pdf",new byte[21*1024*1024]).statusCode()).isEqualTo(400);
        assertThat(upload(company,"a.pdf",pdf("same")).statusCode()).isEqualTo(201);
        var duplicate = upload(company,"b.pdf",pdf("same"));
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(body(duplicate).path("code").asString()).isEqualTo("DUPLICATE_DOCUMENT");
        for (int i = 1; i < DocumentUploadService.MAX_DOCUMENTS; i++) assertThat(upload(company,i+".pdf",pdf("n"+i)).statusCode()).isEqualTo(201);
        var overLimit = upload(company,"over.pdf",pdf("over"));
        assertThat(overLimit.statusCode()).isEqualTo(409);
        assertThat(body(overLimit).path("hint").path("maxDocuments").asInt()).isEqualTo(DocumentUploadService.MAX_DOCUMENTS);
    }

    @Test void ingestFailureMarksFailedAndAllowsReupload() throws Exception {
        long company = company();
        byte[] failing = pdf("simulate-ingest-failure");
        long id = body(upload(company,"fail.pdf",failing)).path("documentId").asLong();
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (documents.findById(id).orElseThrow().getStatus() != DocumentStatus.FAILED && System.nanoTime()<deadline) Thread.sleep(20);
        assertThat(documents.findById(id).orElseThrow().getStatus()).isEqualTo(DocumentStatus.FAILED);
        assertThat(upload(company,"fail.pdf",failing).statusCode()).isEqualTo(201);
    }

    private long company() {
        Company company = new Company(); company.setCompanyName("업로드 테스트");
        return companies.saveAndFlush(company).getCompanyId();
    }
    private static byte[] pdf(String marker) { return ("%PDF-1.4 "+marker+"\n%%EOF").getBytes(StandardCharsets.ISO_8859_1); }
    private HttpResponse<String> upload(long company,String fileName,byte[] content) throws Exception {
        String boundary = "test-"+UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\""+fileName+"\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.writeBytes(content);
        body.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents")).timeout(Duration.ofSeconds(10))
            .header("Content-Type","multipart/form-data; boundary="+boundary).header("Authorization","Bearer "+token(company))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private HttpResponse<String> index(long id,Object result) throws Exception { return request("PATCH","/internal/documents/"+id,result,null); }
    private HttpResponse<String> read(long company,long id) throws Exception { return request("GET","/api/docs/"+id,null,token(company)); }
    private Call awaitCall(long id) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            Call call = CALLS.poll(100,TimeUnit.MILLISECONDS);
            if (call != null && call.path().equals("/documents/"+id+"/ingest")) return call;
        }
        throw new AssertionError("Agent ingest call not received: "+id);
    }
    private HttpResponse<String> request(String method,String path,Object value,String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token != null) request.header("Authorization","Bearer "+token);
        else request.header("X-Internal-Token","test-internal-token");
        request.method(method,value == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(value)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode body(HttpResponse<String> response) { return json.read(response.body()); }
    private String token(long company) {
        Instant now=Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().subject("601")
            .claim("companyId",company).issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
    }
}
