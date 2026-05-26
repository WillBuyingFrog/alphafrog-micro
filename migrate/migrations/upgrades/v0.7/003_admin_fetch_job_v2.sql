-- v0.7 003: Admin 抓取任务 v2 模型（Job + Leaf Task）

-- 1. 新增 admin fetch job 表
CREATE TABLE IF NOT EXISTS alphafrog_admin_fetch_job (
    id BIGSERIAL PRIMARY KEY,
    job_uuid VARCHAR(64) NOT NULL UNIQUE,
    mode VARCHAR(32) NOT NULL,
    label VARCHAR(256),
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    requested_spec JSONB NOT NULL,
    normalized_spec JSONB,
    expanded_task_count INT NOT NULL DEFAULT 0,
    pending_count INT NOT NULL DEFAULT 0,
    running_count INT NOT NULL DEFAULT 0,
    success_count INT NOT NULL DEFAULT 0,
    failure_count INT NOT NULL DEFAULT 0,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE
);

CREATE INDEX IF NOT EXISTS idx_admin_fetch_job_status ON alphafrog_admin_fetch_job(status);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_job_mode ON alphafrog_admin_fetch_job(mode);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_job_created_at ON alphafrog_admin_fetch_job(created_at DESC);

-- 2. 扩展 admin fetch task 表
ALTER TABLE alphafrog_admin_fetch_task
    ADD COLUMN IF NOT EXISTS job_uuid VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_kind VARCHAR(32),
    ADD COLUMN IF NOT EXISTS source_index INT DEFAULT 0,
    ADD COLUMN IF NOT EXISTS task_set_mode VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_job_uuid ON alphafrog_admin_fetch_task(job_uuid);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_source_kind ON alphafrog_admin_fetch_task(source_kind);
