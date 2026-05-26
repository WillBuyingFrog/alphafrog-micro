-- admin 抓取任务持久化表
CREATE TABLE IF NOT EXISTS alphafrog_admin_fetch_task (
    id BIGSERIAL PRIMARY KEY,
    task_uuid VARCHAR(64) NOT NULL UNIQUE,
    template_key VARCHAR(64) NOT NULL,
    task_name VARCHAR(64) NOT NULL,
    task_sub_type INT NOT NULL DEFAULT 1,
    status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    fetched_items_count INT DEFAULT 0,
    message TEXT,
    params_summary VARCHAR(512),
    input_params JSONB,
    dispatch_payload JSONB,
    created_by VARCHAR(128),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    finished_at TIMESTAMP WITH TIME ZONE,
    retry_of_task_uuid VARCHAR(64)
);

CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_status ON alphafrog_admin_fetch_task(status);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_template ON alphafrog_admin_fetch_task(template_key);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_created_at ON alphafrog_admin_fetch_task(created_at);
CREATE INDEX IF NOT EXISTS idx_admin_fetch_task_retry_of ON alphafrog_admin_fetch_task(retry_of_task_uuid);
