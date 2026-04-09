-- v0.7 004: Admin 抓取任务 v2.1 执行选项与异步编排

ALTER TABLE alphafrog_admin_fetch_job
    ADD COLUMN IF NOT EXISTS execution_options JSONB;
