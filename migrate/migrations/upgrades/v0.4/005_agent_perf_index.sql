-- Improve listRuns pagination query with user scoped reverse-time index.
CREATE INDEX IF NOT EXISTS idx_agent_run_user_started_desc
    ON alphafrog_agent_run (user_id, started_at DESC);

