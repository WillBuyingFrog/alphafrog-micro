-- Agent Credit apply records
CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_application (
    id BIGSERIAL PRIMARY KEY,
    application_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    amount INTEGER NOT NULL,
    reason TEXT,
    contact VARCHAR(255),
    status VARCHAR(32) NOT NULL DEFAULT 'APPROVED',
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_user ON alphafrog_agent_credit_application(user_id);
CREATE INDEX IF NOT EXISTS idx_agent_credit_apply_created ON alphafrog_agent_credit_application(created_at);
