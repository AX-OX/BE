package com.axnavigator.recovery;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.mock.web.*;
import com.axnavigator.common.*;
import com.axnavigator.doc.DocumentSignals;
import com.axnavigator.task.TaskSignals;
import static org.assertj.core.api.Assertions.*;

@Tag("recovery")
@EnabledIfEnvironmentVariable(named="RECOVERY_TEST_JDBC_URL", matches=".+")
class CommandQueuePostgresTest {
    static DriverManagerDataSource ds;
    static JdbcTemplate jdbc;
    static String schema;
    CommandQueue queue;
    RecoveryProperties properties;
    DataSourceTransactionManager manager;

    @BeforeAll static void database() throws Exception {
        String url=System.getenv("RECOVERY_TEST_JDBC_URL");
        String user=System.getenv().getOrDefault("RECOVERY_TEST_DB_USER", "postgres");
        String password=System.getenv().getOrDefault("RECOVERY_TEST_DB_PASSWORD", "");
        schema="recovery_"+UUID.randomUUID().toString().replace("-", "");
        var admin=new JdbcTemplate(new DriverManagerDataSource(url,user,password));
        admin.execute("CREATE SCHEMA "+schema);
        ds=new DriverManagerDataSource(url+(url.contains("?")?"&":"?")+"currentSchema="+schema,user,password);
        jdbc=new JdbcTemplate(ds);
        String migration=Files.readString(Path.of("../db/migrations/20261024_durable_ai/001_commands.sql"));
        jdbc.execute(migration.substring(0,migration.indexOf("-- Grant")));
        jdbc.execute("CREATE TABLE ai_tasks(task_id varchar(36) PRIMARY KEY, status text, error_message text, completed_at timestamptz)");
        jdbc.execute("CREATE TABLE documents(document_id bigint PRIMARY KEY, status text)");
    }
    @AfterAll static void drop() {
        if (jdbc!=null) jdbc.execute("DROP SCHEMA "+schema+" CASCADE");
    }
    @BeforeEach void setup() {
        jdbc.execute("TRUNCATE ai_commands, ai_command_inputs, ai_tasks, documents CASCADE");
        properties=new RecoveryProperties(); properties.setEnabled(true);
        manager=new DataSourceTransactionManager(ds);
        queue=queueInstance();
    }
    CommandQueue queueInstance() {
        var proxy=new ProxyFactory(new CommandQueue(jdbc,new JsonSupport(),properties));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(manager,new AnnotationTransactionAttributeSource()));
        return (CommandQueue)proxy.getProxy();
    }
    String task() {
        String id=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ai_tasks(task_id,status) VALUES (?,'QUEUED')",id);
        queue.enqueue(new TaskSignals.Run(id,1L,7L,"회사 분석",true,List.of()));
        return id;
    }
    void expire(CommandQueue.Claimed command) {
        jdbc.update("UPDATE ai_commands SET lease_until=clock_timestamp()-INTERVAL '1 second' WHERE command_id=?",command.commandId());
    }
    @Test void committedCommandSurvivesNewQueueAndRolledBackCommandDoesNotExist() {
        String id=task();
        var tx=new TransactionTemplate(manager);
        tx.executeWithoutResult(status->{ queue.enqueue(new TaskSignals.Confirm(id,7L)); status.setRollbackOnly(); });
        var command=queueInstance().claim();
        assertThat(command.taskId()).isEqualTo(id);
        assertThat(command.payload().path("requestText").asString()).isEqualTo("회사 분석");
        assertThat(command.payload().has("taskId")).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_commands",Integer.class)).isEqualTo(1);
    }
    @Test void concurrentWorkersClaimOneCommandOnlyOnce() throws Exception {
        task();
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<CommandQueue.Claimed>> calls=new ArrayList<>();
            for(int i=0;i<12;i++) calls.add(()->queueInstance().claim());
            int claimed=0;
            for(var future:executor.invokeAll(calls)) if(future.get()!=null) claimed++;
            assertThat(claimed).isEqualTo(1);
        }
    }
    @Test void expiredWorkerCannotHeartbeatOrWriteAfterHandover() throws Exception {
        String id=task(); var old=queue.claim(); expire(old); var current=queue.claim();
        assertThat(current.leaseToken()).isNotEqualTo(old.leaseToken());
        assertThatThrownBy(()->queue.heartbeat(old.commandId(),old.leaseToken())).isInstanceOf(ApiException.class);
        var response=callback(id,old,200);
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?",String.class,id)).isEqualTo("QUEUED");
        assertThat(callback(id,current,200).getStatus()).isEqualTo(200);
    }
    @Test void callbackAndLeaseCheckCommitTogetherAndRollbackOnHttpError() throws Exception {
        String id=task(); var command=queue.claim();
        assertThat(callback(id,command,400).getStatus()).isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?",String.class,id)).isEqualTo("QUEUED");
    }
    @Test void callbackCannotWriteAnotherTask() throws Exception {
        task(); var command=queue.claim();
        assertThat(callback(UUID.randomUUID().toString(),command,200).getStatus()).isEqualTo(403);
    }
    @Test void missingLeaseIsForbidden() throws Exception {
        var response=new MockHttpServletResponse();
        new CommandFenceFilter(queue,jdbc,new JsonSupport(),manager).doFilter(
            new MockHttpServletRequest("PATCH","/internal/tasks/x"),response,(req,res)->fail("must not execute"));
        assertThat(response.getStatus()).isEqualTo(403);
    }
    @Test void newerUserDecisionFencesOldCallbacksAndIsNotLost() throws Exception {
        String id=task(); var run=queue.claim();
        jdbc.update("UPDATE ai_tasks SET status='RUNNING' WHERE task_id=?",id);
        queue.enqueue(new TaskSignals.Confirm(id,7L));
        assertThat(callback(id,run,200).getStatus()).isEqualTo(409);
        expire(run);
        var replay=queue.claim(); assertThat(replay.superseded()).isTrue();
        queue.complete(replay.commandId(),replay.leaseToken());
        assertThat(queue.claim().kind()).isEqualTo("CONFIRM");
    }
    @Test void completionRequiresPersistedResultAndSurvivesLostAcknowledgement() {
        String id=task(); var command=queue.claim();
        assertThatThrownBy(()->queue.complete(command.commandId(),command.leaseToken())).isInstanceOf(ApiException.class);
        jdbc.update("UPDATE ai_tasks SET status='WAITING_APPROVAL' WHERE task_id=?",id);
        queue.complete(command.commandId(),command.leaseToken());
        assertThat(queueInstance().claim()).isNull();
    }
    @Test void exhaustedOldCommandCannotFailNewlyApprovedWork() {
        String id=task(); var run=queue.claim();
        jdbc.update("UPDATE ai_tasks SET status='RUNNING' WHERE task_id=?",id);
        queue.enqueue(new TaskSignals.Resume(id,com.axnavigator.task.TaskDtos.Decision.APPROVE,7L,null));
        properties.setMaxAttempts(1);
        queue.fail(run.commandId(),run.leaseToken(),true);
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?",String.class,id)).isEqualTo("RUNNING");
        assertThat(queue.claim().kind()).isEqualTo("RESUME");
    }
    @Test void retriesHaveBackoffAndExhaustionTerminatesTask() {
        properties.setMaxAttempts(2);
        String id=task(); var first=queue.claim(); queue.fail(first.commandId(),first.leaseToken(),true);
        assertThat(queue.claim()).isNull();
        jdbc.update("UPDATE ai_commands SET available_at=clock_timestamp()-INTERVAL '1 second'");
        var last=queue.claim(); assertThat(last.attempt()).isEqualTo(2);
        expire(last); queue.maintenance();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?",String.class,id)).isEqualTo("FAILED");
        assertThat(queue.claim()).isNull();
    }
    @Test void pdfSurvivesRestartAndIsDeletedOnCompletionOrPermanentFailure() {
        pdf(1); var command=queueInstance().claim();
        assertThat(queue.input(command.commandId(),command.leaseToken())).isEqualTo("%PDF-test".getBytes(StandardCharsets.UTF_8));
        jdbc.update("UPDATE documents SET status='INDEXED' WHERE document_id=1");
        queue.complete(command.commandId(),command.leaseToken());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_inputs",Integer.class)).isZero();
        pdf(2); var failed=queue.claim(); queue.fail(failed.commandId(),failed.leaseToken(),false);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_inputs",Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM documents WHERE document_id=2",String.class)).isEqualTo("FAILED");
    }
    @Test void expiredPdfIsDeletedEvenWithAnActiveLease() {
        pdf(1); var command=queue.claim();
        jdbc.update("UPDATE ai_commands SET input_expires_at=clock_timestamp()-INTERVAL '1 second'");
        queue.maintenance();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_command_inputs",Integer.class)).isZero();
        assertThatThrownBy(()->queue.input(command.commandId(),command.leaseToken())).isInstanceOf(ApiException.class);
    }
    @Test void maintenanceLeavesApprovalWaitIntact() {
        String id=task(); var command=queue.claim();
        jdbc.update("UPDATE ai_tasks SET status='WAITING_APPROVAL' WHERE task_id=?",id);
        properties.setMaxAttempts(1); expire(command); queue.maintenance();
        assertThat(jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?",String.class,id)).isEqualTo("WAITING_APPROVAL");
    }
    void pdf(long id) {
        jdbc.update("INSERT INTO documents(document_id,status) VALUES (?,'UPLOADED')",id);
        queue.enqueue(new DocumentSignals.Ingest(id,1L,"회사.pdf","application/pdf","%PDF-test".getBytes(StandardCharsets.UTF_8)));
    }
    MockHttpServletResponse callback(String id,CommandQueue.Claimed command,int status) throws Exception {
        var request=new MockHttpServletRequest("PATCH","/internal/tasks/"+id);
        request.addHeader("X-Command-Id",command.commandId()); request.addHeader("X-Command-Lease",command.leaseToken());
        request.setContent("{}".getBytes(StandardCharsets.UTF_8));
        var response=new MockHttpServletResponse();
        new CommandFenceFilter(queue,jdbc,new JsonSupport(),manager).doFilter(request,response,(req,res)->{
            jdbc.update("UPDATE ai_tasks SET status='RUNNING' WHERE task_id=?",id);
            ((jakarta.servlet.http.HttpServletResponse)res).setStatus(status);
        });
        return response;
    }
}
