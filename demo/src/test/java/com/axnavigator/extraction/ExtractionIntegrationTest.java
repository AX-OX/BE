package com.axnavigator.extraction;

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
import com.axnavigator.common.JsonSupport;
import com.axnavigator.company.*;
import com.axnavigator.support.IntegrationTest;
import com.axnavigator.task.*;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;

/** 실제 PostgreSQL + HTTP mock Agent로 추출 저장, 확정 대기, 사용자 확정, 기업 데이터 저장, Agent 재개를 검증한다. */
@IntegrationTest
class ExtractionIntegrationTest {
    record Call(String path, String body) {}
    static final BlockingQueue<Call> CALLS = new LinkedBlockingQueue<>();
    static final HttpServer AGENT;
    static {
        try {
            AGENT = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            AGENT.createContext("/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                CALLS.add(new Call(exchange.getRequestURI().getPath(),body));
                exchange.sendResponseHeaders(202,-1); exchange.close();
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
    @Autowired JsonSupport json;
    @Autowired AiTaskRepository tasks;
    @Autowired CompanyRepository companies;
    @Autowired DepartmentRepository departments;
    @Autowired BusinessProcessRepository processes;
    @Autowired ProcessMetricRepository metrics;
    @Autowired ItSystemRepository systems;
    @Autowired ExtractionDraftRepository drafts;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void extractConfirmAndResumeFlow() throws Exception {
        String token = signupAndLogin("extract-flow@client.example", "가온테스트");
        long company = companyOf(token);
        long documentId = indexedDocument(token);

        String id = createTask(token);
        JsonNode run = json.read(awaitCall("/tasks/"+id+"/run").body());
        assertThat(run.path("companyDataConfirmed").asBoolean()).isFalse();
        assertThat(run.path("documentIds").get(0).asLong()).isEqualTo(documentId);

        assertThat(event(id,"CONFIRMATION_REQUIRED").statusCode()).isEqualTo(409);
        var invalid = request("POST","/internal/extractions",Map.of("taskId",id,"documentIds",List.of(documentId),"payload",payload("d9","USER_INPUT")),null);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(body(invalid).path("hint").path("validKeys").get(0).asString()).isEqualTo("d1");
        assertThat(request("POST","/internal/extractions",Map.of("taskId",id,"documentIds",List.of(999999),"payload",payload("d1","EXTRACTED")),null).statusCode()).isEqualTo(400);
        var saved = request("POST","/internal/extractions",Map.of("taskId",id,"documentIds",List.of(documentId),"payload",payload("d1","EXTRACTED")),null);
        assertThat(saved.statusCode()).withFailMessage(saved.body()).isEqualTo(200);
        long draftId = body(saved).path("draftId").asLong();
        assertThat(body(request("POST","/internal/extractions",Map.of("taskId",id,"documentIds",List.of(documentId),"payload",payload("d1","EXTRACTED")),null)).path("draftId").asLong()).isEqualTo(draftId);

        assertThat(event(id,"CONFIRMATION_REQUIRED").statusCode()).isEqualTo(204);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.WAITING_CONFIRMATION);
        JsonNode draft = body(request("GET","/api/ai/tasks/"+id+"/extraction",null,token));
        assertThat(draft.path("status").asString()).isEqualTo("DRAFT");
        assertThat(draft.path("payload").path("processes").get(0).path("name").asString()).isEqualTo("규정 문의 응답");
        String other = signupAndLogin("extract-other@client.example", "다른회사");
        assertThat(request("GET","/api/ai/tasks/"+id+"/extraction",null,other).statusCode()).isEqualTo(403);

        assertThat(request("PUT","/api/ai/tasks/"+id+"/extraction/confirm",payload("d9","USER_INPUT"),token).statusCode()).isEqualTo(400);
        assertThat(request("PUT","/api/ai/tasks/"+id+"/extraction/confirm",payload("d1","MEASURED"),token).statusCode()).isEqualTo(400);
        var confirmed = request("PUT","/api/ai/tasks/"+id+"/extraction/confirm",payload("d1","USER_INPUT"),token);
        assertThat(confirmed.statusCode()).withFailMessage(confirmed.body()).isEqualTo(202);
        assertThat(json.read(awaitCall("/tasks/"+id+"/confirm").body()).path("userId").asLong()).isPositive();
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.RUNNING);
        assertThat(drafts.findById(draftId).orElseThrow().getStatus()).isEqualTo(DraftStatus.CONFIRMED);
        assertThat(request("PUT","/api/ai/tasks/"+id+"/extraction/confirm",payload("d1","USER_INPUT"),token).statusCode()).isEqualTo(409);

        Company stored = companies.findById(company).orElseThrow();
        assertThat(stored.getIndustry()).isEqualTo("제조"); assertThat(stored.getEmployeeCount()).isEqualTo(120);
        var departmentRows = departments.findByCompanyIdOrderByDepartmentId(company);
        assertThat(departmentRows).extracting(com.axnavigator.company.Department::getDepartmentName).containsExactly("인사팀");
        var processRows = processes.findAll().stream().filter(p -> p.getDepartmentId().equals(departmentRows.getFirst().getDepartmentId())).toList();
        assertThat(processRows).hasSize(2);
        long processId = processRows.stream().filter(p -> p.getProcessName().equals("규정 문의 응답")).findFirst().orElseThrow().getProcessId();
        var metricRows = metrics.findAll().stream().filter(m -> m.getProcessId().equals(processId)).toList();
        assertThat(metricRows).extracting(ProcessMetric::getPeriodStart)
            .containsExactlyInAnyOrder(LocalDate.of(2026,7,1),LocalDate.of(2026,8,1),LocalDate.of(2026,9,1));
        assertThat(metricRows).allSatisfy(m -> assertThat(m.getDataSource()).isEqualTo(DataSourceType.USER_INPUT));
        assertThat(systems.findAll().stream().filter(s -> s.getCompanyId().equals(company)).toList()).hasSize(1);

        JsonNode metricView = body(request("GET","/internal/companies/"+company+"/metrics",null,null)).get(0);
        assertThat(metricView.path("monthlyAvgCount").decimalValue()).isEqualByComparingTo("120");
        assertThat(metricView.path("dataConfidence").asString()).isEqualTo("SURVEY");
        assertThat(metricView.path("missingMonths").size()).isZero();

        // 확정한 업무 유형으로 시드와 같은 템플릿을 받는다. 지표가 없는 업무는 metricsSufficient=false.
        assertThat(processRows).extracting(BusinessProcess::getWorkType).containsExactlyInAnyOrder(WorkType.KNOWLEDGE_QA, WorkType.GUIDANCE);
        JsonNode candidates = body(request("GET","/internal/companies/"+company+"/solution-candidates",null,null));
        assertThat(candidates.size()).isEqualTo(2);
        assertThat(candidates.get(0).path("processId").asLong()).isEqualTo(processId);
        assertThat(candidates.get(0).path("solutionId").asLong()).isEqualTo(501);
        assertThat(candidates.get(0).path("metricsSufficient").asBoolean()).isTrue();
        assertThat(candidates.get(1).path("solutionId").asLong()).isEqualTo(502);
        assertThat(candidates.get(1).path("metricsSufficient").asBoolean()).isFalse();
        assertThat(body(request("POST","/internal/ax/roi",Map.of("processId",processId,"solutionId",501),null)).path("dataConfidence").asString()).isEqualTo("SURVEY");

        // 추천할 과제가 없으면 Agent는 성숙도만 저장하고 로드맵 없이 끝낸다.
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","DONE","result",Map.of("roadmap",Map.of())),null).statusCode()).isEqualTo(409);
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","DONE"),null).statusCode()).isEqualTo(409);
        assertThat(request("POST","/internal/assessments",Map.of("taskId",id,"scores",Map.of("data",40,"infra",40,"automation",30,"aiCapability",20,"governance",30),
            "criteriaVersion","v1.0","evidence",List.of(Map.of("documentId",documentId,"section","p.1"))),null).statusCode()).isEqualTo(200);
        var finished = new LinkedHashMap<String,Object>(); finished.put("roadmap",null); finished.put("answer","AX 성숙도 진단을 완료했습니다.");
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","DONE","result",finished),null).statusCode()).isEqualTo(200);
        assertThat(tasks.findById(id).orElseThrow().getStatus()).isEqualTo(TaskStatus.DONE);

        String next = createTask(token);
        JsonNode nextRun = json.read(awaitCall("/tasks/"+next+"/run").body());
        assertThat(nextRun.path("companyDataConfirmed").asBoolean()).isTrue();
        assertThat(nextRun.path("documentIds").size()).isZero();
        assertThat(request("POST","/internal/extractions",Map.of("taskId",next,"documentIds",List.of(),"payload",payload("d1","EXTRACTED")),null).statusCode()).isEqualTo(409);
    }

    private Map<String,Object> payload(String departmentKey,String dataSource) {
        var process = new LinkedHashMap<String,Object>();
        process.put("key","p1"); process.put("departmentKey",departmentKey); process.put("name","규정 문의 응답");
        process.put("purpose","반복 문의 응답"); process.put("steps",List.of("문의 접수","규정 검색")); process.put("standardizationLevel",0.8);
        process.put("requiresApproval",false); process.put("workType","KNOWLEDGE_QA"); process.put("source",Map.of("documentId",1,"page",2));
        var second = new LinkedHashMap<String,Object>(Map.of("key","p2","departmentKey","d1","name","온보딩 안내","requiresApproval",false,"workType","GUIDANCE"));
        var metric = new LinkedHashMap<String,Object>();
        metric.put("processKey","p1"); metric.put("monthlyCount",120); metric.put("avgMinutes",15); metric.put("hourlyLaborCostKrw",35000);
        metric.put("errorRate",0.02); metric.put("dataSource",dataSource);
        return Map.of("company",Map.of("industry","제조","employeeCount",120),
            "departments",List.of(Map.of("key","d1","name","인사팀")),
            "processes",List.of(process,second),
            "metrics",List.of(metric),
            "systems",List.of(Map.of("key","s1","name","그룹웨어","type","GROUPWARE","apiAvailable",true,"dataAccessLevel","OPEN","cloudBased",true,"processKeys",List.of("p1","p2"))));
    }
    private long indexedDocument(String token) throws Exception {
        String boundary = "test-"+UUID.randomUUID();
        var body = new java.io.ByteArrayOutputStream();
        body.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"intro.pdf\"\r\nContent-Type: application/pdf\r\n\r\n%PDF-1.4 "+UUID.randomUUID()+"\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
        var uploaded = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents")).timeout(Duration.ofSeconds(10))
            .header("Content-Type","multipart/form-data; boundary="+boundary).header("Authorization","Bearer "+token)
            .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        long id = body(uploaded).path("documentId").asLong();
        assertThat(request("PATCH","/internal/documents/"+id,Map.of("status","INDEXED","pageCount",1,"pages",List.of(Map.of("page",1,"text","회사 소개"))),null).statusCode()).isEqualTo(200);
        return id;
    }
    private String signupAndLogin(String email,String name) throws Exception {
        assertThat(request("POST","/api/auth/signup",Map.of("email",email,"password","ChangeMe123!","name",name),null).statusCode()).isEqualTo(201);
        return body(request("POST","/api/auth/login",Map.of("email",email,"password","ChangeMe123!"),null)).path("accessToken").asString();
    }
    private long companyOf(String token) throws Exception {
        String claims = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]),StandardCharsets.UTF_8);
        return json.read(claims).path("companyId").asLong();
    }
    private String createTask(String token) throws Exception {
        var created = request("POST","/api/ai/tasks",Map.of("requestText","우리 회사 AX 성숙도를 진단해 줘"),token);
        assertThat(created.statusCode()).isEqualTo(202);
        return body(created).path("taskId").asString();
    }
    private HttpResponse<String> event(String id,String type) throws Exception {
        return request("POST","/internal/tasks/"+id+"/events",Map.of("type",type,"taskId",id,"ts",OffsetDateTime.now().toString(),"node","extract","data",Map.of()),null);
    }
    private Call awaitCall(String path) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            Call call = CALLS.poll(100,TimeUnit.MILLISECONDS);
            if (call != null && call.path().equals(path)) return call;
        }
        throw new AssertionError("Agent call not received: "+path);
    }
    private HttpResponse<String> request(String method,String path,Object value,String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token != null) request.header("Authorization","Bearer "+token);
        else request.header("X-Internal-Token","test-internal-token");
        request.method(method,value == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(value)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private JsonNode body(HttpResponse<String> response) { return json.read(response.body()); }
}
