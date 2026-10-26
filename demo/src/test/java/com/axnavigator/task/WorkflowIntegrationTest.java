package com.axnavigator.task;

import java.net.*;
import java.net.http.*;
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
import com.axnavigator.ax.*;
import com.axnavigator.doc.*;
import com.axnavigator.common.JsonSupport;
import com.axnavigator.support.IntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

/** 실제 PostgreSQL + HTTP mock Agent로 작업·승인·등록·SSE 계약을 검증한다. */
@IntegrationTest
class WorkflowIntegrationTest {
    record Call(String path, String body) {}
    static final BlockingQueue<Call> CALLS = new LinkedBlockingQueue<>();
    static final HttpServer AGENT;
    static {
        try {
            AGENT = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            AGENT.createContext("/tasks/", exchange -> {
                String body = new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                CALLS.add(new Call(exchange.getRequestURI().getPath(),body));
                exchange.sendResponseHeaders(body.contains("simulate-agent-failure") ? 503 : 202,-1); exchange.close();
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
    @Autowired AiTaskRepository tasks;
    @Autowired AxAssessmentRepository assessments;
    @Autowired AxRoadmapRepository roadmaps;
    @Autowired RoadmapItemRepository items;
    @Autowired AxProjectRepository projects;
    @Autowired DocumentRepository documents;
    final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    @Test void fullFlowFromRequestThroughApprovalRegistrationAndDone() throws Exception {
        String id = create();
        assertThat(getTask(id,602).statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/ai/tasks/"+id+"/stream",null,token(602),false).statusCode()).isEqualTo(403);
        assertThat(request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(602),false).statusCode()).isEqualTo(403);
        assertThat(request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(601),false).statusCode()).isEqualTo(409);
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("result",Map.of("approval",Map.of("decision","APPROVE"),"marker","before")),null,true).statusCode()).isEqualTo(200);
        assertThat(body(getTask(id,601)).path("result").has("approval")).isFalse();
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("budgetKrw",300000000L),null,true).statusCode()).isEqualTo(200);
        assertThat(tasks.findById(id).orElseThrow().getBudgetKrw()).isEqualTo(300000000L);

        var assessment = request("POST","/internal/assessments",Map.of("taskId",id,"scores",Map.of("data",10,"infra",20,"automation",30,"aiCapability",40,"governance",50),"criteriaVersion","v1.0","evidence",List.of(Map.of("documentId",401,"section","평가 근거"))),null,true);
        assertThat(assessment.statusCode()).isEqualTo(200);
        long assessmentId = body(assessment).path("assessmentId").asLong();
        assertThat(assessments.findById(assessmentId).orElseThrow().getTotalScore()).isEqualByComparingTo("28.00");

        JsonNode portfolio = body(request("POST","/internal/ax/portfolio",Map.of("companyId",1,"budgetKrw",300000000L),null,true));
        List<Map<String,Object>> itemRequests = roadmapItems(portfolio);
        Map<String,Object> roadmap = new LinkedHashMap<>(Map.of("taskId",id,"companyId",1,"assessmentId",assessmentId,"roadmapName","통합 테스트 로드맵","items",itemRequests));
        assertThat(request("POST","/internal/roadmaps",roadmap,null,true).statusCode()).isEqualTo(409);
        event(id,"APPROVAL_REQUIRED",Map.of("roadmap",Map.of("roadmapName","초안")));
        assertThat(body(getTask(id,601)).path("status").asString()).isEqualTo("WAITING_APPROVAL");
        assertThat(request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE","reason","검토 완료"),token(601),false).statusCode()).isEqualTo(202);
        Call resume = awaitCall(id,"resume");
        assertThat(json.read(resume.body()).path("decision").asString()).isEqualTo("APPROVE");
        assertThat(json.read(resume.body()).path("userId").asLong()).isEqualTo(601);
        JsonNode approval = body(getTask(id,601)).path("result").path("approval");
        assertThat(approval.path("decidedAt").asString()).endsWith("+09:00");
        assertThat(request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(601),false).statusCode()).isEqualTo(409);
        request("PATCH","/internal/tasks/"+id,Map.of("result",Map.of("marker","after","approval",Map.of("decision","REJECT"))),null,true);
        assertThat(body(getTask(id,601)).path("result").path("approval")).isEqualTo(approval);

        Map<String,Object> badItem = new LinkedHashMap<>(itemRequests.getFirst()); badItem.put("axScore",0);
        roadmap.put("items",List.of(badItem));
        var mismatch = request("POST","/internal/roadmaps",roadmap,null,true);
        assertThat(mismatch.statusCode()).isEqualTo(400);
        assertThat(body(mismatch).path("code").asString()).isEqualTo("CALCULATION_MISMATCH");
        assertThat(body(mismatch).path("hint").has("expected")).isTrue();
        roadmap.put("items",itemRequests);
        JsonNode saved = body(request("POST","/internal/roadmaps",roadmap,null,true));
        assertThat(saved.has("roadmapId")).isTrue();
        assertThat(body(request("POST","/internal/roadmaps",roadmap,null,true))).isEqualTo(saved);
        var stored = roadmaps.findById(saved.path("roadmapId").asLong()).orElseThrow();
        assertThat(stored.getApprovedBy()).isEqualTo(601);
        assertThat(stored.getTotalAllocatedKrw()).isEqualTo(295000000);
        assertThat(stored.getBudgetLimitKrw()).isEqualTo(300000000L);
        assertThat(Duration.between(stored.getApprovedAt(), OffsetDateTime.parse(approval.path("decidedAt").asString()).toLocalDateTime()).abs()).isLessThan(Duration.ofMillis(1));

        for (JsonNode itemId : saved.path("itemIds")) {
            Map<String,Object> projectBody = Map.of("itemId",itemId.asLong(),"ownerDepartmentId",103,"projectName","등록 테스트");
            var projectResponse = request("POST","/internal/projects",projectBody,null,true);
            assertThat(projectResponse.statusCode()).isEqualTo(200);
            JsonNode project = body(projectResponse);
            assertThat(body(request("POST","/internal/projects",projectBody,null,true))).isEqualTo(project);
            long projectId = project.path("projectId").asLong();
            assertThat(projects.findById(projectId).orElseThrow().getProjectStatus()).isEqualTo(ProjectStatus.REGISTERED);
            assertThat(request("PATCH","/internal/projects/"+projectId+"/status",Map.of("status","COMPLETED"),null,true).statusCode()).isEqualTo(409);
            for (String status : List.of("IN_PROGRESS","ON_HOLD","IN_PROGRESS","COMPLETED"))
                assertThat(request("PATCH","/internal/projects/"+projectId+"/status",Map.of("status",status),null,true).statusCode()).isEqualTo(200);
        }
        event(id,"DONE",Map.of("result",Map.of("portfolio",portfolio,"approval",Map.of("decision","REJECT"))));
        JsonNode finalTask = body(getTask(id,601));
        assertThat(finalTask.path("status").asString()).isEqualTo("DONE");
        assertThat(finalTask.path("result").path("approval")).isEqualTo(approval);
        var stream = request("GET","/api/ai/tasks/"+id+"/stream",null,token(601),false);
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("\"type\":\"DONE\"").contains("\"decision\":\"APPROVE\"");
        assertThat(tasks.findById(id).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","RUNNING"),null,true).statusCode()).isEqualTo(409);
        assertThat(request("GET","/api/ai/tasks?mine=true",null,token(601),false).body()).contains(id);
        assertThat(request("DELETE","/api/ai/tasks/"+id,null,token(602),false).statusCode()).isEqualTo(403);
        assertThat(request("DELETE","/api/ai/tasks/"+id,null,token(601),false).statusCode()).isEqualTo(204);
        assertThat(request("GET","/api/ai/tasks?mine=true",null,token(601),false).body()).doesNotContain(id);
        assertThat(getTask(id,601).statusCode()).isEqualTo(404);
        assertThat(roadmaps.findByTaskId(id)).isPresent();
    }

    @Test void rejectClosesStreamAndCannotBeOverwrittenByAgent() throws Exception {
        String id = create(); event(id,"APPROVAL_REQUIRED",Map.of());
        HttpResponse<java.io.InputStream> stream = openStream(id);
        assertThat(stream.headers().firstValue("X-Accel-Buffering")).contains("no");
        request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","REJECT","reason","보류"),token(601),false);
        assertThat(awaitCall(id,"resume").body()).contains("REJECT");
        try (var input = stream.body()) { assertThat(new String(input.readAllBytes())).contains(":ping"); }
        assertThat(body(getTask(id,601)).path("status").asString()).isEqualTo("REJECTED");
        assertThat(tasks.findById(id).orElseThrow().getCompletedAt()).isNotNull();
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","RUNNING"),null,true).statusCode()).isEqualTo(409);
        assertThat(request("POST","/internal/roadmaps",Map.of("taskId",id,"companyId",1,"roadmapName","거절된 초안","items",List.of(roadmapItems(body(request("POST","/internal/ax/portfolio",Map.of("companyId",1),null,true))).getFirst())),null,true).statusCode()).isEqualTo(409);
    }

    @Test void sseRelaysProgressAndFailureAndStoresFailure() throws Exception {
        String id = create();
        HttpResponse<java.io.InputStream> stream = openStream(id);
        event(id,"TOKEN",Map.of("text","분석 중"));
        event(id,"FAILED",Map.of("message","문서 검색 실패","traceUrl","https://example.test/trace"));
        try (var input = stream.body()) {
            String text = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertThat(text).contains("\"type\":\"TOKEN\"").contains("\"type\":\"FAILED\"").contains("문서 검색 실패");
        }
        assertThat(body(getTask(id,601)).path("status").asString()).isEqualTo("FAILED");
        assertThat(tasks.findById(id).orElseThrow().getErrorMessage()).isEqualTo("문서 검색 실패");
    }

    @Test void missingAreasAndInvalidInputsReturnContractErrors() throws Exception {
        String id = create();
        var response = request("POST","/internal/assessments",Map.of("taskId",id,"scores",Map.of("data",50),"criteriaVersion","v1.0","evidence",List.of()),null,true);
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).path("hint").path("missingAreas").size()).isEqualTo(4);
        assertThat(request("POST","/api/ai/tasks",Map.of("requestText"," "),token(601),false).statusCode()).isEqualTo(400);
        assertThat(request("GET","/api/ai/tasks?mine=false",null,token(601),false).statusCode()).isEqualTo(400);
        assertThat(request("GET","/api/ai/tasks/no-such-task",null,token(601),false).statusCode()).isEqualTo(404);
        assertThat(request("DELETE","/api/ai/tasks/"+id,null,token(601),false).statusCode()).isEqualTo(409);
        assertThat(request("DELETE","/api/ai/tasks/no-such-task",null,token(601),false).statusCode()).isEqualTo(404);
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("result",List.of()),null,true).statusCode()).isEqualTo(400);
        assertThat(request("POST","/internal/tasks/"+id+"/events",Map.of("type","TOKEN","taskId","wrong","ts",OffsetDateTime.now().toString(),"node","collect","data",Map.of()),null,true).statusCode()).isEqualTo(400);
        assertThat(request("PATCH","/internal/tasks/"+id,Map.of("status","DONE"),null,true).statusCode()).isEqualTo(409);
    }

    @Test void documentEndpointChecksCompanyAndMasksContacts() throws Exception {
        var response = request("GET","/api/docs/401",null,token(601),false);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(body(response).path("content").asString()).contains("[이메일 마스킹]").contains("[전화번호 마스킹]").doesNotContain("demo.101@gaon.example");
        assertThat(request("GET","/api/docs/401",null,token(601,2),false).statusCode()).isEqualTo(403);
        assertThat(request("GET","/api/docs/99999",null,token(601),false).statusCode()).isEqualTo(404);
    }


    @Test void concurrentApprovalsAndRegistrationsAreSerialized() throws Exception {
        String id = create(); event(id,"APPROVAL_REQUIRED",Map.of());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var a = executor.submit(() -> request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(601),false));
            var b = executor.submit(() -> request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(601),false));
            assertThat(List.of(a.get(10,TimeUnit.SECONDS).statusCode(),b.get(10,TimeUnit.SECONDS).statusCode())).containsExactlyInAnyOrder(202,409);
            awaitCall(id,"resume");
            var portfolio = body(request("POST","/internal/ax/portfolio",Map.of("companyId",1),null,true));
            var roadmap = Map.of("taskId",id,"companyId",1,"roadmapName","동시 등록","items",roadmapItems(portfolio));
            var first = executor.submit(() -> request("POST","/internal/roadmaps",roadmap,null,true));
            var second = executor.submit(() -> request("POST","/internal/roadmaps",roadmap,null,true));
            var r1 = first.get(10,TimeUnit.SECONDS); var r2 = second.get(10,TimeUnit.SECONDS);
            assertThat(r1.statusCode()).isEqualTo(200); assertThat(r2.statusCode()).isEqualTo(200);
            assertThat(body(r1)).isEqualTo(body(r2));
            long item = body(r1).path("itemIds").get(0).asLong();
            var project = Map.of("itemId",item,"ownerDepartmentId",103,"projectName","동시 등록");
            var p1 = executor.submit(() -> request("POST","/internal/projects",project,null,true));
            var p2 = executor.submit(() -> request("POST","/internal/projects",project,null,true));
            var response1=p1.get(10,TimeUnit.SECONDS);var response2=p2.get(10,TimeUnit.SECONDS);
            assertThat(response1.statusCode()).isEqualTo(200);assertThat(response2.statusCode()).isEqualTo(200);
            assertThat(body(response1)).isEqualTo(body(response2));
        }
    }

    @Test void roadmapRejectsDuplicateProcessMissingDependencyAndBudgetOverflow() throws Exception {
        String id=create();event(id,"APPROVAL_REQUIRED",Map.of());
        request("POST","/api/ai/tasks/"+id+"/approval",Map.of("decision","APPROVE"),token(601),false);awaitCall(id,"resume");
        request("PATCH","/internal/tasks/"+id,Map.of("budgetKrw",300000000L),null,true);
        var portfolio=body(request("POST","/internal/ax/portfolio",Map.of("companyId",1,"budgetKrw",300000000L),null,true));
        var selected=roadmapItems(portfolio);
        var duplicate = new LinkedHashMap<String,Object>(selected.getFirst());duplicate.put("priorityOrder",4);
        var duplicates = new ArrayList<Map<String,Object>>(selected); duplicates.add(duplicate);
        assertThat(request("POST","/internal/roadmaps",Map.of("taskId",id,"companyId",1,"roadmapName","중복","items",duplicates),null,true).statusCode()).isEqualTo(400);
        var dependent=selected.stream().filter(item -> item.get("solutionId").equals(508L)).findFirst().orElseThrow();
        assertThat(request("POST","/internal/roadmaps",Map.of("taskId",id,"companyId",1,"roadmapName","선행 누락","items",List.of(dependent)),null,true).statusCode()).isEqualTo(400);
        List<Map<String,Object>> overBudget=new ArrayList<>();int priority=0;
        for (long solution : new long[]{505,506,507,509}) {
            var score=body(request("POST","/internal/ax/roi",Map.of("processId",solution-300,"solutionId",solution),null,true));
            var wrapper=json.object();wrapper.set("selected",json.tree(List.of(score)));
            var item=roadmapItems(wrapper).getFirst();item.put("priorityOrder",++priority);overBudget.add(item);
        }
        assertThat(request("POST","/internal/roadmaps",Map.of("taskId",id,"companyId",1,"roadmapName","예산 초과","items",overBudget),null,true).statusCode()).isEqualTo(400);
        assertThat(roadmaps.findByTaskId(id)).isEmpty();

        // 작업 예산이 없으면 예산 검증 없이 저장하고 예산 스냅샷은 비워 둔다.
        String unlimited=create();event(unlimited,"APPROVAL_REQUIRED",Map.of());
        request("POST","/api/ai/tasks/"+unlimited+"/approval",Map.of("decision","APPROVE"),token(601),false);awaitCall(unlimited,"resume");
        assertThat(request("POST","/internal/roadmaps",Map.of("taskId",unlimited,"companyId",1,"roadmapName","예산 없음","items",overBudget),null,true).statusCode()).isEqualTo(200);
        var stored=roadmaps.findByTaskId(unlimited).orElseThrow();
        assertThat(stored.getBudgetLimitKrw()).isNull();
        assertThat(stored.getTotalAllocatedKrw()).isGreaterThan(300000000L);
    }

    @Test void agentHttpFailureIsPersistedAsFailed() throws Exception {
        var created=request("POST","/api/ai/tasks",Map.of("requestText","simulate-agent-failure"),token(601),false);
        assertThat(created.statusCode()).isEqualTo(202);
        String id=body(created).path("taskId").asString();awaitCall(id,"run");
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (!tasks.findById(id).orElseThrow().getStatus().equals(TaskStatus.FAILED) && System.nanoTime()<deadline) Thread.sleep(20);
        assertThat(body(getTask(id,601)).path("status").asString()).isEqualTo("FAILED");
        assertThat(tasks.findById(id).orElseThrow().getErrorMessage()).contains("AI Agent 연결에 실패했습니다");
    }

    @Test void streamAccessErrorsRemainJsonWithEventStreamAcceptHeader() throws Exception {
        String id=create();
        var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai/tasks/"+id+"/stream"))
            .timeout(Duration.ofSeconds(5)).header("Authorization","Bearer "+token(602)).header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).path("code").asString()).isEqualTo("FORBIDDEN");
    }

    private List<Map<String,Object>> roadmapItems(JsonNode portfolio) {
        List<Map<String,Object>> result = new ArrayList<>(); int index = 0;
        for (JsonNode score : portfolio.path("selected")) {
            var item = new LinkedHashMap<String,Object>(); var base = score.path("scenarios").path("base");
            item.put("processId",score.path("processId").asLong()); item.put("solutionId",score.path("solutionId").asLong());
            item.put("itemName","도입 과제"); item.put("priorityOrder",++index); item.put("phase","SHORT"); item.put("startMonth",1); item.put("durationMonths",3);
            item.put("allocatedBudgetKrw",score.path("firstYearTcoKrw").asLong());
            for (String field : List.of("axScore","impactScore","feasibilityScore")) item.put(field,score.path(field).decimalValue());
            item.put("expectedAnnualSavingKrw",base.path("annualSavingKrw").asLong()); item.put("roiPct",base.path("roiPct").decimalValue());
            item.put("paybackMonths",base.path("paybackMonths").isNull() ? null : base.path("paybackMonths").decimalValue());
            item.put("threeYearNetValueKrw",base.path("threeYearNetKrw").asLong()); item.put("rationale","근거에 따른 추천이며 사람 검토를 거칩니다."); item.put("evidence",List.of());
            result.add(item);
        }
        return result;
    }
    private String create() throws Exception {
        var response = request("POST","/api/ai/tasks",Map.of("requestText","AX 로드맵 추천"),token(601),false);
        assertThat(response.statusCode()).isEqualTo(202);
        String id = body(response).path("taskId").asString();
        Call run = awaitCall(id,"run"); assertThat(json.read(run.body()).path("companyId").asLong()).isEqualTo(1);
        return id;
    }
    private Call awaitCall(String id,String operation) throws Exception {
        long deadline = System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime()<deadline) {
            Call call = CALLS.poll(100,TimeUnit.MILLISECONDS);
            if (call != null && call.path().equals("/tasks/"+id+"/"+operation)) return call;
        }
        throw new AssertionError("Agent call not received: "+operation);
    }
    private void event(String id,String type,Object data) throws Exception {
        var response = request("POST","/internal/tasks/"+id+"/events",Map.of("type",type,"taskId",id,"ts",OffsetDateTime.now().toString(),"node","evaluate","data",data),null,true);
        assertThat(response.statusCode()).withFailMessage(response.body()).isEqualTo(204);
    }
    private HttpResponse<String> getTask(String id,long user) throws Exception { return request("GET","/api/ai/tasks/"+id,null,token(user),false); }
    private HttpResponse<java.io.InputStream> openStream(String id) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/ai/tasks/"+id+"/stream"))
            .timeout(Duration.ofSeconds(10)).header("Authorization","Bearer "+token(601)).header("Accept","text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }
    private HttpResponse<String> request(String method,String path,Object value,String token,boolean internal) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");
        if (token != null) request.header("Authorization","Bearer "+token);
        if (internal) request.header("X-Internal-Token","test-internal-token");
        request.method(method,value == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.write(value)));
        return http.send(request.build(),HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
    }
    private JsonNode body(HttpResponse<String> response) { return json.read(response.body()); }
    private String token(long id) { return token(id,1); }
    private String token(long id,long company) {
        Instant now=Instant.now();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(),JwtClaimsSet.builder().subject(Long.toString(id))
            .claim("companyId",company).issuedAt(now).expiresAt(now.plusSeconds(600)).build())).getTokenValue();
    }
}
