-- ALP-17 P0: 审批审计、额度流水、用户禁用联动与幂等治理

ALTER TABLE alphafrog_user
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS disabled_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS disabled_reason TEXT,
    ADD COLUMN IF NOT EXISTS status_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP;

UPDATE alphafrog_user
SET status = 'ACTIVE'
WHERE status IS NULL OR status = '';

CREATE INDEX IF NOT EXISTS idx_user_status ON alphafrog_user(status);
CREATE INDEX IF NOT EXISTS idx_user_status_updated_at ON alphafrog_user(status_updated_at);

ALTER TABLE alphafrog_agent_credit_application
    ADD COLUMN IF NOT EXISTS processed_by VARCHAR(64),
    ADD COLUMN IF NOT EXISTS process_reason TEXT,
    ADD COLUMN IF NOT EXISTS version INT NOT NULL DEFAULT 0;

ALTER TABLE alphafrog_agent_credit_application
    ALTER COLUMN status SET DEFAULT 'PENDING';

CREATE INDEX IF NOT EXISTS idx_credit_app_status_created
    ON alphafrog_agent_credit_application(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credit_app_user_status
    ON alphafrog_agent_credit_application(user_id, status);

CREATE TABLE IF NOT EXISTS alphafrog_agent_credit_ledger (
    id BIGSERIAL PRIMARY KEY,
    ledger_id VARCHAR(64) NOT NULL UNIQUE,
    user_id VARCHAR(64) NOT NULL,
    biz_type VARCHAR(32) NOT NULL,
    delta INTEGER NOT NULL,
    balance_before INTEGER NOT NULL,
    balance_after INTEGER NOT NULL,
    source_type VARCHAR(32) NOT NULL,
    source_id VARCHAR(64) NOT NULL,
    operator_id VARCHAR(64),
    idempotency_key VARCHAR(128),
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_ledger_biz_source
    ON alphafrog_agent_credit_ledger(biz_type, source_id);
CREATE INDEX IF NOT EXISTS idx_credit_ledger_user_created
    ON alphafrog_agent_credit_ledger(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_credit_ledger_biz_created
    ON alphafrog_agent_credit_ledger(biz_type, created_at DESC);

CREATE TABLE IF NOT EXISTS alphafrog_admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    audit_id VARCHAR(64) NOT NULL UNIQUE,
    operator_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    before_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    after_json JSONB NOT NULL DEFAULT '{}'::jsonb,
    reason TEXT,
    idempotency_key VARCHAR(128),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_target
    ON alphafrog_admin_audit_log(target_type, target_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_audit_operator_created
    ON alphafrog_admin_audit_log(operator_id, created_at DESC);

CREATE TABLE IF NOT EXISTS alphafrog_admin_idempotency (
    id BIGSERIAL PRIMARY KEY,
    operator_id VARCHAR(64) NOT NULL,
    action VARCHAR(64) NOT NULL,
    target_id VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PROCESSING',
    response_json TEXT NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uniq_admin_idem
    ON alphafrog_admin_idempotency(operator_id, action, target_id, idempotency_key);
CREATE INDEX IF NOT EXISTS idx_admin_idem_updated_at
    ON alphafrog_admin_idempotency(updated_at DESC);
