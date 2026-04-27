-- 配置类型注册
CREATE TABLE IF NOT EXISTS alphafrog_config_type (
    id            SERIAL PRIMARY KEY,
    name          VARCHAR(64) NOT NULL UNIQUE,
    data_id       VARCHAR(128) NOT NULL,
    config_group  VARCHAR(128) NOT NULL DEFAULT 'alphafrog-config',
    service_name  VARCHAR(64) NOT NULL,
    schema_json   JSONB NOT NULL,
    description   TEXT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- 配置快照（版本链）
CREATE TABLE IF NOT EXISTS alphafrog_config_snapshot (
    id            SERIAL PRIMARY KEY,
    type_id       INTEGER NOT NULL REFERENCES alphafrog_config_type(id),
    version       VARCHAR(32) NOT NULL,
    content_json  JSONB NOT NULL,
    content_md5   VARCHAR(32) NOT NULL,
    comment       TEXT,
    created_by    VARCHAR(64),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (type_id, version)
);

CREATE INDEX IF NOT EXISTS idx_config_snapshot_type_id ON alphafrog_config_snapshot(type_id);
CREATE INDEX IF NOT EXISTS idx_config_snapshot_created_at ON alphafrog_config_snapshot(created_at);

-- 当前激活版本
CREATE TABLE IF NOT EXISTS alphafrog_config_active (
    type_id       INTEGER PRIMARY KEY REFERENCES alphafrog_config_type(id),
    snapshot_id   INTEGER NOT NULL REFERENCES alphafrog_config_snapshot(id),
    activated_at  TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    activated_by  VARCHAR(64)
);

-- 操作审计日志
CREATE TABLE IF NOT EXISTS alphafrog_config_audit_log (
    id            SERIAL PRIMARY KEY,
    type_id       INTEGER NOT NULL,
    action        VARCHAR(32) NOT NULL,
    snapshot_id   INTEGER,
    base_version  VARCHAR(32),
    operator_id   VARCHAR(64),
    reason        TEXT,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_config_audit_type_id ON alphafrog_config_audit_log(type_id);
CREATE INDEX IF NOT EXISTS idx_config_audit_created_at ON alphafrog_config_audit_log(created_at);
