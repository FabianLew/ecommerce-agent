-- ADR-0027 stage 1, step 5: the platform AI stop (section 12, gap 9), run admission (section 6.2, gap 8) and the
-- situation recorded for the cooldown (section 7, gap 10).
--
-- AI stop. ai_platform_stop_change is an append-only log of stop and resume decisions; the newest row is the current
-- state, and no row means AI is not stopped. Operations write it with the administration role (like budgets and
-- prices); the runtime cannot change it. The stop is enforced inside the database, so it takes effect on every
-- instance at the next check without a restart:
--   - ai_acquire_run_lease refuses a new agent run while AI is stopped;
--   - ai_mark_sent refuses to send a call that was reserved before the stop and releases its reservation, so a
--     reservation granted earlier never allows a call after a later stop.
-- A call already sent when the stop arrives finishes and is settled normally. The stop does not touch deterministic
-- monitoring or the stop of business mutations (ADR-0020).
--
-- Admission. An agent run holds a lease while it runs. ai_acquire_run_lease admits a run only while the store has
-- fewer active leases than the store limit and the platform fewer than the global limit; the check and the insert
-- are serialised by one transaction-scoped advisory lock, so the limits hold across all instances. A lease ends when
-- the run releases it or when it expires (a crashed worker), so a lost run never blocks a store for longer than the
-- lease. The limits come from the backend (the agent's run policy and operator.ai-governance), never from a model.

-- Cooldown (section 7, gap 10). A run records the situation it analysed as a short fingerprint set by its
-- proposer; a new paid analysis of the same opportunity inside the cooldown is started only when the situation
-- changed materially. Null for runs started before this migration (treated as changed).
ALTER TABLE agent_run
    ADD COLUMN situation text CHECK (situation IS NULL OR length(situation) BETWEEN 1 AND 200);
CREATE INDEX agent_run_opportunity_started ON agent_run (store_id, opportunity_id, started_at DESC);

CREATE TABLE ai_platform_stop_change (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    stopped    boolean     NOT NULL,
    reason     text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    changed_by text        NOT NULL CHECK (length(changed_by) BETWEEN 1 AND 200),
    changed_at timestamptz NOT NULL DEFAULT now()
);

ALTER TABLE ai_platform_stop_change ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_platform_stop_change FORCE ROW LEVEL SECURITY;
-- Operations add rows and read them; with forced row security nobody can change or delete a row.
CREATE POLICY administration_read ON ai_platform_stop_change FOR SELECT TO operator_migrator USING (true);
CREATE POLICY administration_insert ON ai_platform_stop_change FOR INSERT TO operator_migrator WITH CHECK (true);
GRANT SELECT ON ai_platform_stop_change TO operator_system;
CREATE POLICY governance ON ai_platform_stop_change FOR SELECT TO operator_system USING (true);

CREATE TABLE ai_run_lease (
    agent_run_id    uuid PRIMARY KEY,
    organization_id uuid        NOT NULL,
    store_id        uuid        NOT NULL,
    automation_type text        NOT NULL CHECK (automation_type ~ '^[a-z0-9-]{1,64}$'),
    acquired_at     timestamptz NOT NULL,
    expires_at      timestamptz NOT NULL,
    released_at     timestamptz,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (agent_run_id, store_id) REFERENCES agent_run (id, store_id),
    CHECK (expires_at > acquired_at),
    CHECK (released_at IS NULL OR released_at >= acquired_at)
);
CREATE INDEX ai_run_lease_open ON ai_run_lease (store_id, expires_at) WHERE released_at IS NULL;

ALTER TABLE ai_run_lease ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_run_lease FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_run_lease FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
GRANT SELECT, INSERT, UPDATE ON ai_run_lease TO operator_system;
CREATE POLICY governance ON ai_run_lease TO operator_system USING (true) WITH CHECK (true);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- Internal: whether AI is stopped now. Not callable by the runtime.
CREATE FUNCTION ai_platform_stopped()
    RETURNS boolean
    LANGUAGE sql STABLE SET search_path = pg_catalog, public AS
$$
    SELECT coalesce((SELECT c.stopped FROM public.ai_platform_stop_change c ORDER BY c.id DESC LIMIT 1), false)
$$;

-- The current stop state, for the application's metrics and status (no row: not stopped).
CREATE FUNCTION ai_platform_stop_state()
    RETURNS TABLE (stopped boolean, reason text, changed_by text, changed_at timestamptz)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
    SELECT coalesce(c.stopped, false), c.reason, c.changed_by, c.changed_at
    FROM (SELECT 1) one
    LEFT JOIN LATERAL (SELECT * FROM public.ai_platform_stop_change ORDER BY id DESC LIMIT 1) c ON true
$$;

-- Admits an agent run of the bound store. Answers GRANTED (also for a repeated call of a run that holds an open
-- lease), AI_STOPPED, STORE_LIMIT or GLOBAL_LIMIT. Only a GRANTED run may reserve and send model calls.
CREATE FUNCTION ai_acquire_run_lease(p_agent_run_id uuid, p_automation_type text, p_max_store_runs integer,
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

-- Ends the lease of a run of the bound store. Releasing twice, or a lease that expired, changes nothing.
CREATE FUNCTION ai_release_run_lease(p_agent_run_id uuid)
    RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    UPDATE public.ai_run_lease l
    SET released_at = greatest(now(), l.acquired_at)
    WHERE l.agent_run_id = p_agent_run_id AND l.organization_id = v_org AND l.store_id = v_store
      AND l.released_at IS NULL;
    RETURN FOUND;
END
$$;

-- ai_mark_sent of V31, plus the stop: a reserved call is not sent after a stop, and its reservation is released.
CREATE OR REPLACE FUNCTION ai_mark_sent(p_reservation_id uuid)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_reservation public.ai_budget_reservation%ROWTYPE;
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
    IF public.ai_platform_stopped() THEN
        PERFORM public.ai_release(p_reservation_id, 'AI_STOPPED');
        RETURN 'STOPPED';
    END IF;
    UPDATE public.ai_budget_reservation
    SET state = 'IN_PROGRESS', sent_at = now(), updated_at = now()
    WHERE id = p_reservation_id;
    RETURN 'SEND';
END
$$;

-- Functions start without PUBLIC (V31); the runtime gets the definer functions only.
REVOKE EXECUTE ON FUNCTION ai_platform_stopped() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_platform_stop_state() FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_acquire_run_lease(uuid, text, integer, integer, interval) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_release_run_lease(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_platform_stop_state() TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_acquire_run_lease(uuid, text, integer, integer, interval) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_release_run_lease(uuid) TO operator_runtime;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
