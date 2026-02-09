-- Agent Service DDL (PostgreSQL)
-- Tables for managing AI agent runs and events
-- Prefix: alphafrog_agent_

CREATE TABLE IF NOT EXISTS alphafrog_agent_run (
    id VARCHAR(64) PRIMARY KEY, -- using UUID/RunID string directly
    user_id VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'RECEIVED' CHECK (status IN ('RECEIVED', 'PLANNING', 'EXECUTING', 'WAITING', 'SUMMARIZING', 'COMPLETED', 'FAILED', 'CANCELED', 'EXPIRED')),
    current_step INT NOT NULL DEFAULT 0,
    max_steps INT NOT NULL DEFAULT 12,
    plan_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    snapshot_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    last_error TEXT,
    ttl_expires_at TIMESTAMPTZ,
    started_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMPTZ,
    ext JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX IF NOT EXISTS idx_agent_run_user ON alphafrog_agent_run (user_id);
CREATE INDEX IF NOT EXISTS idx_agent_run_status ON alphafrog_agent_run (status);
CREATE INDEX IF NOT EXISTS idx_agent_run_updated ON alphafrog_agent_run (updated_at);

CREATE TABLE IF NOT EXISTS alphafrog_agent_run_event (
    id BIGSERIAL PRIMARY KEY,
    run_id VARCHAR(64) NOT NULL REFERENCES alphafrog_agent_run(id) ON DELETE CASCADE,
    seq INT NOT NULL, -- sequence number within the run
    event_type VARCHAR(64) NOT NULL, -- e.g., PLAN_CREATED, TOOL_CALLING, TOOL_OUTPUT
    payload_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, seq)
);

CREATE INDEX IF NOT EXISTS idx_agent_run_event_run ON alphafrog_agent_run_event (run_id);
