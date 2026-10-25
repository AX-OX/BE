package com.axnavigator.recovery;

import java.io.*;
import java.nio.charset.StandardCharsets;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.filter.OncePerRequestFilter;
import com.axnavigator.common.*;
import tools.jackson.databind.JsonNode;

/** Fence the lease and the business write in ONE transaction. Runs after Spring Security. */
@Component
@Order(0)
@ConditionalOnProperty(name="ax.recovery.enabled", havingValue="true")
public class CommandFenceFilter extends OncePerRequestFilter {
    private final CommandQueue queue;
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final TransactionTemplate tx;
    public CommandFenceFilter(CommandQueue queue, JdbcTemplate jdbc, JsonSupport json, PlatformTransactionManager manager) {
        this.queue=queue; this.jdbc=jdbc; this.json=json; this.tx=new TransactionTemplate(manager);
    }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        String path=request.getRequestURI();
        if (!request.getMethod().equals("POST") && !request.getMethod().equals("PATCH")) return true;
        return !(path.startsWith("/internal/tasks/") || path.startsWith("/internal/documents/")
            || path.equals("/internal/extractions") || path.equals("/internal/assessments")
            || path.equals("/internal/roadmaps") || path.equals("/internal/projects") || path.startsWith("/internal/projects/"));
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String id=request.getHeader("X-Command-Id"), token=request.getHeader("X-Command-Lease");
        if (id==null || token==null) {
            JsonErrorWriter.write(response, ErrorCode.FORBIDDEN, "작업 실행 권한이 필요합니다."); return;
        }
        byte[] body=request.getInputStream().readNBytes(22 * 1024 * 1024 + 1);
        if (body.length > 22 * 1024 * 1024) {
            JsonErrorWriter.write(response, ErrorCode.VALIDATION_ERROR, "콜백 본문이 너무 큽니다."); return;
        }
        try {
            tx.executeWithoutResult(status -> {
                var command=queue.lockWritable(id, token);
                authorize(command, request.getRequestURI(), body);
                try { chain.doFilter(new ReplayRequest(request, body), response); }
                catch (IOException | ServletException ex) { throw new FilterFailure(ex); }
                if (response.getStatus() >= 400) status.setRollbackOnly();
            });
        } catch (ApiException ex) { JsonErrorWriter.write(response, ex.code(), ex.getMessage()); }
        catch (FilterFailure ex) {
            if (ex.getCause() instanceof IOException io) throw io;
            throw (ServletException)ex.getCause();
        }
    }
    private void authorize(CommandQueue.Entry command, String path, byte[] body) {
        if (path.startsWith("/internal/documents/")) {
            if (!path.equals("/internal/documents/"+command.documentId()) || !command.kind().equals("INGEST")) forbidden();
            return;
        }
        if (command.taskId()==null) { forbidden(); return; }
        if (path.startsWith("/internal/tasks/")) {
            String base="/internal/tasks/"+command.taskId();
            if (!path.equals(base) && !path.equals(base+"/events")) forbidden();
            return;
        }
        JsonNode data;
        try { data=json.read(new String(body, StandardCharsets.UTF_8)); }
        catch (RuntimeException ex) { throw ApiException.validation("유효하지 않은 JSON입니다."); }
        if (data==null || !data.isObject()) throw ApiException.validation("JSON 객체가 필요합니다.");
        String taskId;
        if (path.equals("/internal/projects")) {
            var found=jdbc.queryForList("SELECT r.task_id FROM roadmap_items i JOIN ax_roadmaps r ON r.roadmap_id=i.roadmap_id WHERE i.item_id=?",
                String.class, data.path("itemId").asLong(-1));
            if (found.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND,"로드맵 항목을 찾을 수 없습니다.");
            taskId=found.getFirst();
        } else if (path.startsWith("/internal/projects/")) {
            String[] segments=path.split("/");
            long projectId;
            try { projectId=Long.parseLong(segments[3]); }
            catch (RuntimeException ex) { throw ApiException.validation("프로젝트 ID가 올바르지 않습니다."); }
            var found=jdbc.queryForList("""
                SELECT r.task_id FROM ax_projects p JOIN roadmap_items i ON i.item_id=p.item_id
                JOIN ax_roadmaps r ON r.roadmap_id=i.roadmap_id WHERE p.project_id=?
                """, String.class, projectId);
            if (found.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND,"프로젝트를 찾을 수 없습니다.");
            taskId=found.getFirst();
        } else taskId=data.path("taskId").asString("");
        if (!command.taskId().equals(taskId)) forbidden();
    }
    private static void forbidden() { throw new ApiException(ErrorCode.FORBIDDEN, "다른 작업의 결과를 변경할 수 없습니다."); }
    private static class FilterFailure extends RuntimeException { FilterFailure(Exception cause) { super(cause); } }
    private static class ReplayRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        ReplayRequest(HttpServletRequest request, byte[] body) { super(request); this.body=body; }
        @Override public ServletInputStream getInputStream() {
            var input=new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return input.read(); }
                @Override public boolean isFinished() { return input.available()==0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException(); }
            };
        }
        @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    }
}
