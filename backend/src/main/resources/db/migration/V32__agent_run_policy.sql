-- ADR-0027 stage 1, step 4: the limits of an agent run are a versioned run policy (section 5, gap 5), recorded on
-- the run for audit and reproducibility, next to the price list it already records.
--
-- run_policy  the policy reference, for example inventory-agent-run/1;
-- run_limits  the limits the runtime applied, as key/value text (the cost limit as a decimal string, never a float).
--
-- Both are written once when the run starts. They are null only for runs started before this migration, which used
-- the limits that were then code (the same values as inventory-agent-run/1).

ALTER TABLE agent_run
    ADD COLUMN run_policy text,
    ADD COLUMN run_limits jsonb,
    ADD CONSTRAINT agent_run_policy_recorded
        CHECK ((run_policy IS NULL) = (run_limits IS NULL)),
    ADD CONSTRAINT agent_run_limits_object
        CHECK (run_limits IS NULL OR jsonb_typeof(run_limits) = 'object');

COMMENT ON COLUMN agent_run.run_policy IS 'Versioned run policy of the run (ADR-0027 section 5).';
COMMENT ON COLUMN agent_run.run_limits IS 'Limits the runtime applied to the run, as recorded at its start.';
