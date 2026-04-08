-- 用户与认证相关表（从零初始化）

CREATE TABLE IF NOT EXISTS alphafrog_user (
    user_id BIGSERIAL PRIMARY KEY,
    username VARCHAR(128) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL,
    register_time BIGINT NOT NULL,
    user_type INTEGER,
    user_level INTEGER,
    credit INTEGER,
    status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    disabled_at TIMESTAMPTZ,
    disabled_reason TEXT,
    status_updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (username),
    UNIQUE (email)
);

CREATE INDEX IF NOT EXISTS idx_user_username ON alphafrog_user(username);
CREATE INDEX IF NOT EXISTS idx_user_email ON alphafrog_user(email);
CREATE INDEX IF NOT EXISTS idx_user_status ON alphafrog_user(status);
CREATE INDEX IF NOT EXISTS idx_user_status_updated_at ON alphafrog_user(status_updated_at);
