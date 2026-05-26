-- Allow EXPIRED status for interrupted agent runs.
ALTER TABLE IF EXISTS alphafrog_agent_run
    DROP CONSTRAINT IF EXISTS alphafrog_agent_run_status_check;

ALTER TABLE IF EXISTS alphafrog_agent_run
    ADD CONSTRAINT alphafrog_agent_run_status_check
        CHECK (status IN (
            'RECEIVED',
            'PLANNING',
            'EXECUTING',
            'WAITING',
            'SUMMARIZING',
            'COMPLETED',
            'FAILED',
            'CANCELED',
            'EXPIRED'
        ));

