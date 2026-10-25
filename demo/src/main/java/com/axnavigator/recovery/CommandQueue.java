package com.axnavigator.recovery;

import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.axnavigator.common.*;
import com.axnavigator.doc.DocumentSignals;
import com.axnavigator.task.TaskSignals;
import tools.jackson.databind.JsonNode;

/** Durable, at-least-once commands. All leases use the database clock. */
@Service
public class CommandQueue {
    private final JdbcTemplate jdbc;
    private final JsonSupport json;
    private final RecoveryProperties properties;

    public CommandQueue(JdbcTemplate jdbc, JsonSupport json, RecoveryProperties properties) {
        this.jdbc = jdbc; this.json = json; this.properties = properties;
    }
    public boolean enabled() { return properties.isEnabled(); }
    public record Claimed(String commandId, String aggregateKey, String taskId, Long documentId,
        String kind, JsonNode payload, String leaseToken, int attempt, int leaseSeconds, int schemaVersion, boolean superseded) {}
    public record Entry(String id, String aggregate, String taskId, Long documentId, String kind,
        String state, String token, OffsetDateTime until, int attempts, String payload) {}

    @Transactional
    public void enqueue(TaskSignals.Run value) { enqueue(value.taskId(), null, "RUN", value, null); }
    @Transactional
    public void enqueue(TaskSignals.Resume value) { enqueue(value.taskId(), null, "RESUME", value, null); }
    @Transactional
    public void enqueue(TaskSignals.Confirm value) { enqueue(value.taskId(), null, "CONFIRM", value, null); }
    @Transactional
    public void enqueue(DocumentSignals.Ingest value) {
        enqueue(null, value.documentId(), "INGEST", Map.of("documentId", value.documentId(),
            "companyId", value.companyId(), "fileName", value.fileName(), "contentType", value.contentType()), value.content());
    }
    private void enqueue(String taskId, Long documentId, String kind, Object payload, byte[] content) {
        if (!enabled()) return;
        String id = UUID.randomUUID().toString();
        String aggregate = taskId != null ? "task:" + taskId : "document:" + documentId;
        var stored = json.tree(payload);
        // taskId is in the command envelope, not in the existing Agent request body.
        if (stored.isObject()) ((tools.jackson.databind.node.ObjectNode)stored).remove("taskId");
        jdbc.update("""
            INSERT INTO ai_commands(command_id, aggregate_key, task_id, document_id, kind, payload, input_expires_at)
            VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP + (? * INTERVAL '1 hour') ELSE NULL END)
            """, id, aggregate, taskId, documentId, kind, json.write(stored), content != null, properties.getInputRetentionHours());
        if (content != null) jdbc.update("INSERT INTO ai_command_inputs(command_id, content) VALUES (?, ?)", id, content);
    }

    @Transactional
    public Claimed claim() {
        requireEnabled();
        var rows = jdbc.query("""
            SELECT c.* FROM ai_commands c
            WHERE ((c.state = 'READY' AND c.available_at <= CURRENT_TIMESTAMP)
                OR (c.state = 'LEASED' AND c.lease_until <= CURRENT_TIMESTAMP))
            AND c.attempts < ?
            AND (c.input_expires_at IS NULL OR c.input_expires_at > CURRENT_TIMESTAMP)
            AND NOT EXISTS (SELECT 1 FROM ai_commands prior
                WHERE prior.aggregate_key = c.aggregate_key AND prior.state IN ('READY', 'LEASED')
                AND (prior.created_at, prior.command_id) < (c.created_at, c.command_id))
            ORDER BY c.created_at, c.command_id LIMIT 1 FOR UPDATE OF c SKIP LOCKED
            """, (rs, n) -> entry(rs), properties.getMaxAttempts());
        if (rows.isEmpty()) return null;
        Entry row = rows.getFirst();
        String token = UUID.randomUUID().toString();
        jdbc.update("""
            UPDATE ai_commands SET state='LEASED', lease_token=?, attempts=attempts+1,
                lease_until=CURRENT_TIMESTAMP + (? * INTERVAL '1 second') WHERE command_id=?
            """, token, properties.getLeaseSeconds(), row.id());
        boolean superseded = Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ai_commands WHERE aggregate_key=? AND command_id<>?
                AND (created_at, command_id) > (SELECT created_at, command_id FROM ai_commands WHERE command_id=?))
            """, Boolean.class, row.aggregate(), row.id(), row.id()));
        return new Claimed(row.id(), row.aggregate(), row.taskId(), row.documentId(), row.kind(),
            json.read(row.payload()), token, row.attempts()+1, properties.getLeaseSeconds(), 1, superseded);
    }

    /** Lock held until the caller's transaction commits, including the guarded business write. */
    @Transactional
    public Entry lockLease(String id, String token) {
        requireEnabled();
        var rows = jdbc.query("SELECT * FROM ai_commands WHERE command_id=? FOR UPDATE", (rs, n) -> entry(rs), id);
        if (rows.isEmpty()) throw new ApiException(ErrorCode.NOT_FOUND, "실행 명령이 없습니다.");
        Entry row = rows.getFirst();
        boolean active = Boolean.TRUE.equals(jdbc.queryForObject(
            "SELECT state='LEASED' AND lease_token=? AND lease_until > clock_timestamp() FROM ai_commands WHERE command_id=?",
            Boolean.class, token, id));
        if (!active) throw ApiException.invalidState("작업 처리 권한이 만료되었거나 다른 Worker로 인계되었습니다.");
        return row;
    }

    @Transactional
    public Entry lockWritable(String id, String token) {
        Entry row = lockLease(id, token);
        if (row.taskId() != null) {
            // Serialize with user approval/confirmation before checking for a newer command.
            jdbc.queryForObject("SELECT task_id FROM ai_tasks WHERE task_id=? FOR UPDATE", String.class, row.taskId());
            boolean superseded = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai_commands WHERE aggregate_key=? AND command_id<>?
                    AND (created_at, command_id) > (SELECT created_at, command_id FROM ai_commands WHERE command_id=?))
                """, Boolean.class, row.aggregate(), row.id(), row.id()));
            if (superseded) throw ApiException.invalidState("새 사용자 결정이 저장되어 이전 실행의 콜백을 중단합니다.");
        }
        return row;
    }

    @Transactional
    public void heartbeat(String id, String token) {
        lockLease(id, token);
        jdbc.update("UPDATE ai_commands SET lease_until=clock_timestamp() + (? * INTERVAL '1 second') WHERE command_id=?",
            properties.getLeaseSeconds(), id);
    }

    @Transactional
    public byte[] input(String id, String token) {
        Entry row = lockLease(id, token);
        if (!row.kind().equals("INGEST")) throw ApiException.invalidState("PDF 색인 명령이 아닙니다.");
        if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT input_expires_at > clock_timestamp() FROM ai_commands WHERE command_id=?", Boolean.class, id)))
            throw ApiException.invalidState("PDF 임시 보관 기간이 만료되었습니다.");
        return jdbc.queryForObject("SELECT content FROM ai_command_inputs WHERE command_id=?", byte[].class, id);
    }

    @Transactional
    public void complete(String id, String token) {
        Entry row = lockLease(id, token);
        // Acknowledgement may only follow persisted business state, never precede it.
        String state = row.taskId() != null
            ? jdbc.queryForObject("SELECT status FROM ai_tasks WHERE task_id=?", String.class, row.taskId())
            : jdbc.queryForObject("SELECT status FROM documents WHERE document_id=?", String.class, row.documentId());
        Set<String> allowed = row.taskId() != null
            ? Set.of("WAITING_CONFIRMATION", "WAITING_APPROVAL", "DONE", "FAILED", "REJECTED") : Set.of("INDEXED", "FAILED");
        // The next user command can already have moved the task back to RUNNING.
        boolean nextCommand = row.taskId() != null && Boolean.TRUE.equals(jdbc.queryForObject("""
            SELECT EXISTS(SELECT 1 FROM ai_commands WHERE aggregate_key=? AND command_id<>? AND state IN ('READY','LEASED')
                AND created_at >= (SELECT created_at FROM ai_commands WHERE command_id=?))
            """, Boolean.class, row.aggregate(), row.id(), row.id()));
        if (!allowed.contains(state) && !nextCommand) throw ApiException.invalidState("작업 결과가 저장되지 않았습니다.");
        jdbc.update("UPDATE ai_commands SET state='DONE', completed_at=clock_timestamp(), lease_until=NULL WHERE command_id=?", id);
        jdbc.update("DELETE FROM ai_command_inputs WHERE command_id=?", id);
    }

    @Transactional
    public void fail(String id, String token, boolean retryable) {
        Entry row = lockLease(id, token);
        if (retryable && row.attempts() < properties.getMaxAttempts()) {
            int delay = Math.min(60, 1 << Math.min(row.attempts(), 6));
            jdbc.update("""
                UPDATE ai_commands SET state='READY', lease_token=NULL, lease_until=NULL,
                    available_at=clock_timestamp() + (? * INTERVAL '1 second'), last_error='RETRYABLE_FAILURE' WHERE command_id=?
                """, delay, id);
        } else dead(row, retryable ? "RETRY_EXHAUSTED" : "INVALID_INPUT");
    }

    @Transactional
    public void maintenance() {
        if (!enabled()) return;
        var expired = jdbc.query("""
            SELECT * FROM ai_commands WHERE state IN ('READY','LEASED')
            AND ((input_expires_at <= CURRENT_TIMESTAMP) OR
                (attempts >= ? AND (state='READY' OR lease_until <= CURRENT_TIMESTAMP)))
            ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
            """, (rs, n) -> entry(rs), properties.getMaxAttempts());
        for (Entry row : expired) dead(row, "EXPIRED_OR_EXHAUSTED");
        jdbc.update("DELETE FROM ai_commands WHERE state IN ('DONE','DEAD') AND completed_at < CURRENT_TIMESTAMP - (? * INTERVAL '1 day')",
            properties.getCommandRetentionDays());
    }

    private void dead(Entry row, String code) {
        if (row.taskId() != null) {
            jdbc.queryForObject("SELECT task_id FROM ai_tasks WHERE task_id=? FOR UPDATE", String.class, row.taskId());
            boolean advanced = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM ai_commands WHERE aggregate_key=? AND command_id<>?
                    AND (created_at, command_id) > (SELECT created_at, command_id FROM ai_commands WHERE command_id=?))
                """, Boolean.class, row.aggregate(), row.id(), row.id()));
            if (advanced) {
                jdbc.update("UPDATE ai_commands SET state='DONE', completed_at=clock_timestamp(), lease_token=NULL, lease_until=NULL, last_error='SUPERSEDED' WHERE command_id=?", row.id());
                return;
            }
        }
        if (row.taskId() != null) jdbc.update("""
            UPDATE ai_tasks SET status='FAILED', error_message='AI 작업을 복구하지 못했습니다. 다시 요청해 주세요.', completed_at=CURRENT_TIMESTAMP
            WHERE task_id=? AND status IN ('QUEUED','RUNNING')
            """, row.taskId());
        else jdbc.update("UPDATE documents SET status='FAILED' WHERE document_id=? AND status='UPLOADED'", row.documentId());
        jdbc.update("UPDATE ai_commands SET state='DEAD', completed_at=clock_timestamp(), lease_token=NULL, lease_until=NULL, last_error=? WHERE command_id=?", code, row.id());
        jdbc.update("DELETE FROM ai_command_inputs WHERE command_id=?", row.id());
    }

    private static Entry entry(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Entry(rs.getString("command_id"), rs.getString("aggregate_key"), rs.getString("task_id"),
            rs.getObject("document_id", Long.class), rs.getString("kind"), rs.getString("state"), rs.getString("lease_token"),
            rs.getObject("lease_until", OffsetDateTime.class), rs.getInt("attempts"), rs.getString("payload"));
    }
    private void requireEnabled() {
        if (!enabled()) throw ApiException.invalidState("작업 복구 모드가 비활성화되어 있습니다.");
    }
}
