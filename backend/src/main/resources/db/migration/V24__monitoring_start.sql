-- ADR-0025: who started monitoring a store, and when. Execution is refused until it is set.
ALTER TABLE onboarding_progress
    ADD COLUMN monitoring_started_at timestamptz,
    ADD COLUMN monitoring_started_by uuid;
