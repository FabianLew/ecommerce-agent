-- ADR-0027 section 10, platform independence (E-54.1). AI governance knows automations only as registered
-- identifiers; it never names a commerce or ad platform. A run or a reservation of an automation that is not
-- registered is refused (fail closed): ai_acquire_run_lease answers UNKNOWN_AUTOMATION, and the foreign keys keep
-- unregistered identifiers out of policies, leases and reservations.
--
-- Registering an automation is an administrative change made by a migration, never by the runtime.
CREATE TABLE ai_automation_type (
    automation_type text PRIMARY KEY CHECK (automation_type ~ '^[a-z0-9-]{1,64}$'),
    description     text        NOT NULL CHECK (length(description) BETWEEN 1 AND 500),
    registered_at   timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ai_automation_type ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_automation_type FORCE ROW LEVEL SECURITY;
CREATE POLICY administration ON ai_automation_type TO operator_migrator USING (true) WITH CHECK (true);
GRANT SELECT ON ai_automation_type TO operator_system;
CREATE POLICY governance ON ai_automation_type FOR SELECT TO operator_system USING (true);
GRANT SELECT ON ai_automation_type TO operator_runtime;
CREATE POLICY runtime_read ON ai_automation_type FOR SELECT TO operator_runtime USING (true);

INSERT INTO ai_automation_type (automation_type, description)
VALUES ('inventory-agent', 'Inventory Guardian agent: stock-out risk analysis of one product variant');

-- Identifiers already used before this migration are registered as they are, so the foreign keys always hold.
INSERT INTO ai_automation_type (automation_type, description)
SELECT DISTINCT automation_type, 'Registered from existing rows by V34'
FROM (SELECT automation_type FROM ai_budget_policy WHERE automation_type IS NOT NULL
      UNION SELECT automation_type FROM ai_budget_reservation
      UNION SELECT automation_type FROM ai_run_lease) used
ON CONFLICT (automation_type) DO NOTHING;

ALTER TABLE ai_budget_policy
    ADD CONSTRAINT ai_budget_policy_automation_registered
        FOREIGN KEY (automation_type) REFERENCES ai_automation_type (automation_type);
ALTER TABLE ai_budget_reservation
    ADD CONSTRAINT ai_budget_reservation_automation_registered
        FOREIGN KEY (automation_type) REFERENCES ai_automation_type (automation_type);
ALTER TABLE ai_run_lease
    ADD CONSTRAINT ai_run_lease_automation_registered
        FOREIGN KEY (automation_type) REFERENCES ai_automation_type (automation_type);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- ai_acquire_run_lease of V33, plus UNKNOWN_AUTOMATION for an automation that is not registered.
CREATE OR REPLACE FUNCTION ai_acquire_run_lease(p_agent_run_id uuid, p_automation_type text, p_max_store_runs integer,
                                     p_max_global_runs integer, p_ttl interval)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_now timestamptz := now();
    v_lease public.ai_run_lease%ROWTYPE;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_agent_run_id IS NULL OR p_automation_type IS NULL OR p_automation_type !~ '^[a-z0-9-]{1,64}$'
       OR p_max_store_runs IS NULL OR p_max_store_runs < 1 OR p_max_global_runs IS NULL OR p_max_global_runs < 1
       OR p_ttl IS NULL OR p_ttl <= interval '0' OR p_ttl > interval '1 hour' THEN
        RAISE EXCEPTION 'AI_ADMISSION_INVALID' USING ERRCODE = '22023';
    END IF;
    IF public.ai_platform_stopped() THEN
        RETURN 'AI_STOPPED';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM public.ai_automation_type t WHERE t.automation_type = p_automation_type) THEN
        RETURN 'UNKNOWN_AUTOMATION';
    END IF;
    -- One admission at a time across all instances; held until this transaction ends.
    PERFORM pg_advisory_xact_lock(hashtextextended('ai_run_admission', 0));
    SELECT * INTO v_lease FROM public.ai_run_lease l WHERE l.agent_run_id = p_agent_run_id;
    IF FOUND THEN
        IF v_lease.organization_id <> v_org OR v_lease.store_id <> v_store THEN
            RAISE EXCEPTION 'AI_ADMISSION_INVALID' USING ERRCODE = '22023';
        END IF;
        IF v_lease.released_at IS NULL AND v_lease.expires_at > v_now THEN
            RETURN 'GRANTED';
        END IF;
        -- A run is admitted once; a released or expired lease is not renewed.
        RETURN 'LEASE_ENDED';
    END IF;
    IF (SELECT count(*) FROM public.ai_run_lease l
        WHERE l.store_id = v_store AND l.released_at IS NULL AND l.expires_at > v_now) >= p_max_store_runs THEN
        RETURN 'STORE_LIMIT';
    END IF;
    IF (SELECT count(*) FROM public.ai_run_lease l
        WHERE l.released_at IS NULL AND l.expires_at > v_now) >= p_max_global_runs THEN
        RETURN 'GLOBAL_LIMIT';
    END IF;
    INSERT INTO public.ai_run_lease (agent_run_id, organization_id, store_id, automation_type, acquired_at,
                                     expires_at)
    VALUES (p_agent_run_id, v_org, v_store, p_automation_type, v_now, v_now + p_ttl);
    RETURN 'GRANTED';
END
$$;

RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
