-- Apply as the schema owner, before enabling AX_RECOVERY_ENABLED.
-- No existing task/document data is rewritten. PostgreSQL 16+.
BEGIN;
CREATE TABLE IF NOT EXISTS ai_commands (
    command_id varchar(36) PRIMARY KEY,
    aggregate_key varchar(80) NOT NULL,
    task_id varchar(36),
    document_id bigint,
    kind varchar(16) NOT NULL CHECK (kind IN ('RUN', 'CONFIRM', 'RESUME', 'INGEST')),
    payload text NOT NULL,
    state varchar(16) NOT NULL DEFAULT 'READY' CHECK (state IN ('READY', 'LEASED', 'DONE', 'DEAD')),
    lease_token varchar(36),
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0,
    available_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at timestamptz,
    input_expires_at timestamptz,
    last_error varchar(64),
    CONSTRAINT uq_ai_commands_operation UNIQUE (aggregate_key, kind),
    CHECK ((task_id IS NOT NULL) <> (document_id IS NOT NULL))
);
CREATE INDEX IF NOT EXISTS ix_ai_commands_pending ON ai_commands (available_at, created_at)
    WHERE state IN ('READY', 'LEASED');
CREATE TABLE IF NOT EXISTS ai_command_inputs (
    command_id varchar(36) PRIMARY KEY REFERENCES ai_commands(command_id) ON DELETE CASCADE,
    content bytea NOT NULL CHECK (octet_length(content) BETWEEN 1 AND 20971520)
);
COMMIT;

-- Grant SELECT, INSERT, UPDATE, DELETE on BOTH tables to the actual runtime role.
-- Example for the existing deployment (run as the table owner):
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_commands, ai_command_inputs TO axox_app_rw;
