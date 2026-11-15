-- ADR-0027 section 12, scoped AI stops (E-54.8). Next to the platform stop (V33), AI can be stopped for one store,
-- for one automation on every store, or for one automation on one store. The four stops are independent: each one
-- is checked on its own, resuming one never resumes another, and none is switched on automatically.
--
-- ai_stop_change is an append-only log of decisions; per target the newest row is the current state, and no row means
-- not stopped. Platform operators decide through the application (ai_change_stop, audited); the runtime never writes
-- the table directly. Like the platform stop it is enforced inside the database:
--   - ai_acquire_run_lease refuses a new run of a stopped store or automation (STORE_STOPPED, AUTOMATION_STOPPED);
--   - ai_mark_sent refuses to send a reserved call of a stopped store or automation and releases its reservation, so
--     a run already going stops before its next paid call.
CREATE TABLE ai_stop_change (
    id              bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    organization_id uuid,
    store_id        uuid,
    automation_type text REFERENCES ai_automation_type (automation_type),
    stopped         boolean     NOT NULL,
    reason          text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by      text        NOT NULL CHECK (length(changed_by) BETWEEN 1 AND 200),
    changed_at      timestamptz NOT NULL DEFAULT now(),
    target_key      text GENERATED ALWAYS AS (
                        coalesce(store_id::text, '*') || ':' || coalesce(automation_type, '*')) STORED,
    CHECK ((store_id IS NULL) = (organization_id IS NULL)),
    -- The platform-wide stop of everything is ai_platform_stop_change, written only with the administration role.
    CHECK (store_id IS NOT NULL OR automation_type IS NOT NULL),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
CREATE INDEX ai_stop_change_target ON ai_stop_change (target_key, id DESC);

ALTER TABLE ai_stop_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_stop_change FORCE ROW LEVEL SECURITY;
-- With forced row security and no update or delete policy nobody can change or remove a decision. A store reads the
-- decisions about itself (not the platform-wide automation stops).
CREATE POLICY store_isolation ON ai_stop_change FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
GRANT SELECT, INSERT ON ai_stop_change TO operator_system;
CREATE POLICY governance_read ON ai_stop_change FOR SELECT TO operator_system USING (true);
CREATE POLICY governance_insert ON ai_stop_change FOR INSERT TO operator_system WITH CHECK (true);
CREATE POLICY administration_read ON ai_stop_change FOR SELECT TO operator_migrator USING (true);
CREATE POLICY administration_insert ON ai_stop_change FOR INSERT TO operator_migrator WITH CHECK (true);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- Internal: why AI is stopped for a run or call of this store and automation now, or null. The platform stop first,
-- then the store, then the automation on every store, then the automation on this store. Not callable by the
-- runtime.
CREATE FUNCTION ai_stop_reason(p_store_id uuid, p_automation_type text)
    RETURNS text
    LANGUAGE sql STABLE SET search_path = pg_catalog, public AS
$$
    SELECT CASE
        WHEN public.ai_platform_stopped() THEN 'AI_STOPPED'
        WHEN coalesce((SELECT c.stopped FROM public.ai_stop_change c
                       WHERE c.target_key = p_store_id::text || ':*' ORDER BY c.id DESC LIMIT 1), false)
            THEN 'STORE_STOPPED'
        WHEN coalesce((SELECT c.stopped FROM public.ai_stop_change c
                       WHERE c.target_key = '*:' || p_automation_type ORDER BY c.id DESC LIMIT 1), false)
            OR coalesce((SELECT c.stopped FROM public.ai_stop_change c
                         WHERE c.target_key = p_store_id::text || ':' || p_automation_type
                         ORDER BY c.id DESC LIMIT 1), false)
            THEN 'AUTOMATION_STOPPED'
    END
$$;

-- Records a stop or resume decision for one target. Answers CHANGED, or UNCHANGED when the target is already in that
-- state (nothing is written). The caller is checked by the application: only platform operators reach it.
CREATE FUNCTION ai_change_stop(p_organization_id uuid, p_store_id uuid, p_automation_type text, p_stopped boolean,
                               p_reason text, p_changed_by text)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_key text := coalesce(p_store_id::text, '*') || ':' || coalesce(p_automation_type, '*');
    v_current boolean;
BEGIN
    IF (p_store_id IS NULL AND p_automation_type IS NULL) OR (p_store_id IS NULL) <> (p_organization_id IS NULL)
       OR p_stopped IS NULL
       OR p_reason IS NULL OR length(p_reason) NOT BETWEEN 1 AND 500
       OR p_changed_by IS NULL OR length(p_changed_by) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'AI_STOP_INVALID' USING ERRCODE = '22023';
    END IF;
    IF p_automation_type IS NOT NULL
       AND NOT EXISTS (SELECT 1 FROM public.ai_automation_type t WHERE t.automation_type = p_automation_type) THEN
        RAISE EXCEPTION 'AI_STOP_TARGET_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    -- One decision per target at a time, so two operators cannot both pass the state check.
    PERFORM pg_advisory_xact_lock(hashtextextended('ai_stop:' || v_key, 0));
    v_current := coalesce((SELECT c.stopped FROM public.ai_stop_change c WHERE c.target_key = v_key
                           ORDER BY c.id DESC LIMIT 1), false);
    IF v_current = p_stopped THEN
        RETURN 'UNCHANGED';
    END IF;
    INSERT INTO public.ai_stop_change (organization_id, store_id, automation_type, stopped, reason, changed_by)
    -- The foreign key refuses a store that is not in the organization.
    VALUES (p_organization_id, p_store_id, p_automation_type, p_stopped, p_reason, p_changed_by);
    RETURN 'CHANGED';
END
$$;

-- The decisions, newest first: every target's history, or with p_active_only the targets stopped now.
CREATE FUNCTION ai_stop_changes(p_active_only boolean, p_limit integer)
    RETURNS TABLE (id bigint, organization_id uuid, store_id uuid, automation_type text, stopped boolean,
                   reason text, changed_by text, changed_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
BEGIN
    IF p_active_only IS NULL OR p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'AI_STOP_INVALID' USING ERRCODE = '22023';
    END IF;
    IF p_active_only THEN
        RETURN QUERY
        SELECT l.id, l.organization_id, l.store_id, l.automation_type, l.stopped, l.reason, l.changed_by,
               l.changed_at
        FROM (SELECT DISTINCT ON (c.target_key) c.* FROM public.ai_stop_change c
              ORDER BY c.target_key, c.id DESC) l
        WHERE l.stopped
        ORDER BY l.id DESC
        LIMIT p_limit;
    ELSE
        RETURN QUERY
        SELECT c.id, c.organization_id, c.store_id, c.automation_type, c.stopped, c.reason, c.changed_by,
               c.changed_at
        FROM public.ai_stop_change c ORDER BY c.id DESC LIMIT p_limit;
    END IF;
END
$$;

-- ai_acquire_run_lease of V34, with the scoped stops.
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
    v_stop text;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_agent_run_id IS NULL OR p_automation_type IS NULL OR p_automation_type !~ '^[a-z0-9-]{1,64}$'
       OR p_max_store_runs IS NULL OR p_max_store_runs < 1 OR p_max_global_runs IS NULL OR p_max_global_runs < 1
       OR p_ttl IS NULL OR p_ttl <= interval '0' OR p_ttl > interval '1 hour' THEN
        RAISE EXCEPTION 'AI_ADMISSION_INVALID' USING ERRCODE = '22023';
    END IF;
    v_stop := public.ai_stop_reason(v_store, p_automation_type);
    IF v_stop IS NOT NULL THEN
        RETURN v_stop;
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

-- ai_mark_sent of V33, with the scoped stops: STOPPED for the platform stop, STORE_STOPPED or AUTOMATION_STOPPED
-- for a scoped one; in each case nothing is sent and the reservation is released.
CREATE OR REPLACE FUNCTION ai_mark_sent(p_reservation_id uuid)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_reservation public.ai_budget_reservation%ROWTYPE;
    v_stop text;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    SELECT * INTO v_reservation FROM public.ai_budget_reservation r
    WHERE r.id = p_reservation_id AND r.organization_id = v_org AND r.store_id = v_store
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    IF v_reservation.state = 'RELEASED' THEN
        RETURN 'RELEASED';
    END IF;
    IF v_reservation.state <> 'RESERVED' THEN
        RETURN 'ALREADY_ATTEMPTED';
    END IF;
    IF v_reservation.expires_at <= now() THEN
        RETURN 'EXPIRED';
    END IF;
    v_stop := public.ai_stop_reason(v_store, v_reservation.automation_type);
    IF v_stop IS NOT NULL THEN
        PERFORM public.ai_release(p_reservation_id, v_stop);
        RETURN CASE v_stop WHEN 'AI_STOPPED' THEN 'STOPPED' ELSE v_stop END;
    END IF;
    UPDATE public.ai_budget_reservation
    SET state = 'IN_PROGRESS', sent_at = now(), updated_at = now()
    WHERE id = p_reservation_id;
    RETURN 'SEND';
END
$$;

REVOKE EXECUTE ON FUNCTION ai_stop_reason(uuid, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_change_stop(uuid, uuid, text, boolean, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_stop_changes(boolean, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_change_stop(uuid, uuid, text, boolean, text, text) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_stop_changes(boolean, integer) TO operator_runtime;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
