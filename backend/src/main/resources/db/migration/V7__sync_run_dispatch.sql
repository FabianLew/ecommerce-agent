-- FIX-02: durable workflow start. A RUNNING sync_run is the durable request to start its workflow; the
-- reconciler starts it (deterministic workflow ID) until workflow_started_at confirms the start, and closes
-- runs whose workflow ended without recording an outcome (failure, timeout, cancellation).

ALTER TABLE sync_run ADD COLUMN workflow_started_at timestamptz;

-- Runs created before this migration were started in the same request.
UPDATE sync_run SET workflow_started_at = started_at;

CREATE INDEX sync_run_running ON sync_run (started_at) WHERE status = 'RUNNING';
