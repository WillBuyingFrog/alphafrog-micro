-- Invite codes for invitation-only registration
CREATE TABLE IF NOT EXISTS alphafrog_user_invite_code (
    id BIGSERIAL PRIMARY KEY,
    invite_code VARCHAR(64) NOT NULL UNIQUE,
    created_by BIGINT,
    used_by BIGINT,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ext JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMPTZ,
    used_at TIMESTAMPTZ
);

CREATE INDEX IF NOT EXISTS idx_user_invite_code_status ON alphafrog_user_invite_code(status);
CREATE INDEX IF NOT EXISTS idx_user_invite_code_expires ON alphafrog_user_invite_code(expires_at);
