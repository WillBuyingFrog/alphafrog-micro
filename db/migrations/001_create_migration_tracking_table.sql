-- =============================================
-- 迁移工具元数据表
-- 用于记录已执行的迁移版本
-- =============================================

CREATE TABLE IF NOT EXISTS schema_migrations (
    id SERIAL PRIMARY KEY,
    version VARCHAR(64) NOT NULL UNIQUE,
    description TEXT,
    filename VARCHAR(256) NOT NULL,
    checksum VARCHAR(64),  -- 文件内容的MD5校验和，用于检测篡改
    executed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    execution_time_ms INTEGER,  -- 执行耗时（毫秒）
    executed_by VARCHAR(128),   -- 执行用户
    success BOOLEAN DEFAULT TRUE
);

CREATE INDEX IF NOT EXISTS idx_schema_migrations_version ON schema_migrations(version);
CREATE INDEX IF NOT EXISTS idx_schema_migrations_executed_at ON schema_migrations(executed_at);

-- 添加注释
COMMENT ON TABLE schema_migrations IS '数据库迁移版本跟踪表，由 migrate.py 自动管理';
COMMENT ON COLUMN schema_migrations.version IS '迁移版本号，格式：YYYYMMDD_NNN';
COMMENT ON COLUMN schema_migrations.checksum IS 'SQL文件的MD5校验和';
