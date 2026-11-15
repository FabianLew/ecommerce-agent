-- ADR-0027 section 4.5, reconciliation of uncertain AI calls (E-54.6).
--
-- 1. Platform operators: the people who may decide about platform money (reconcile an uncertain call, correct a
--    conservative reconciliation, and later switch AI off for a store or an automation). The list is written only by
--    reviewed changes with the administration role (operator_migrator); the runtime reads it and cannot change it.
-- 2. The provider's request id is kept on a reservation whose result is unknown, so the call can be looked up with
--    the provider and matched by that id, never by time or amount alone.
-- 3. A correction after a conservative reconciliation is an adjustment entry: the usage ledger row stays as it was,
--    the adjustment records the difference and who decided it, and the balances move by that difference.

CREATE TABLE platform_operator (
    user_id     uuid PRIMARY KEY REFERENCES app_user (id),
    granted_by  text        NOT NULL CHECK (length(granted_by) BETWEEN 1 AND 200),
    reason      text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    granted_at  timestamptz NOT NULL DEFAULT now(),
    revoked_at  timestamptz,
    CHECK (revoked_at IS NULL OR revoked_at >= granted_at)
);
ALTER TABLE platform_operator ENABLE ROW LEVEL SECURITY;
ALTER TABLE platform_operator FORCE ROW LEVEL SECURITY;
GRANT SELECT ON platform_operator TO operator_runtime;
CREATE POLICY platform_read ON platform_operator FOR SELECT TO operator_runtime USING (true);
CREATE POLICY administration ON platform_operator TO operator_migrator USING (true) WITH CHECK (true);

ALTER TABLE ai_budget_reservation
    ADD COLUMN provider_request_id text CHECK (length(provider_request_id) BETWEEN 1 AND 200);
CREATE INDEX ai_budget_reservation_provider_request ON ai_budget_reservation (provider_request_id)
    WHERE provider_request_id IS NOT NULL;

CREATE TABLE ai_usage_adjustment (
    id               uuid PRIMARY KEY,
    organization_id  uuid           NOT NULL,
    store_id         uuid           NOT NULL,
    -- One correction per reservation: a second different one is a conflict for a person to resolve.
    reservation_id   uuid           NOT NULL UNIQUE REFERENCES ai_budget_reservation (id),
    usage_event_id   uuid           NOT NULL UNIQUE REFERENCES ai_usage_event (id),
    outcome          text           NOT NULL CHECK (outcome IN ('CORRECTED_NOT_CHARGED', 'CORRECTED_CHARGED')),
    currency         char(3)        NOT NULL,
    previous_cost    numeric(20, 8) NOT NULL CHECK (previous_cost >= 0),
    corrected_cost   numeric(20, 8) NOT NULL CHECK (corrected_cost >= 0),
    -- corrected_cost - previous_cost; negative when money goes back to the budgets.
    delta            numeric(20, 8) NOT NULL,
    recorded_by      text           NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
    reason           text           NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    created_at       timestamptz    NOT NULL DEFAULT now(),
    CHECK (delta = corrected_cost - previous_cost),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE FUNCTION ai_usage_adjustment_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ai_usage_adjustment rows are append-only (% not allowed)', TG_OP;
END
$$;
CREATE TRIGGER ai_usage_adjustment_append_only BEFORE UPDATE OR DELETE ON ai_usage_adjustment
    FOR EACH ROW EXECUTE FUNCTION ai_usage_adjustment_is_append_only();

ALTER TABLE ai_usage_adjustment ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_adjustment FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_usage_adjustment FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
GRANT SELECT ON ai_usage_adjustment TO operator_runtime;
GRANT SELECT, INSERT ON ai_usage_adjustment TO operator_system;
CREATE POLICY governance ON ai_usage_adjustment TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY administration_read ON ai_usage_adjustment FOR SELECT TO operator_migrator USING (true);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- ai_mark_unknown with the provider's request id, when the provider answered with one. The id is kept once; a
-- later different id does not replace it.
CREATE FUNCTION ai_mark_unknown(p_reservation_id uuid, p_reason text, p_provider_request_id text)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_state text;
BEGIN
    IF p_provider_request_id IS NOT NULL AND length(p_provider_request_id) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'AI_RESERVATION_INVALID' USING ERRCODE = '22023';
    END IF;
    v_state := public.ai_mark_unknown(p_reservation_id, p_reason);
    IF p_provider_request_id IS NOT NULL THEN
        UPDATE public.ai_budget_reservation
        SET provider_request_id = coalesce(provider_request_id, p_provider_request_id)
        WHERE id = p_reservation_id;
    END IF;
    RETURN v_state;
END
$$;

-- Where a reservation belongs, for a platform operator who knows only its id. Returns no row for an unknown id.
CREATE FUNCTION ai_reservation_location(p_reservation_id uuid)
    RETURNS TABLE (organization_id uuid, store_id uuid)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
    SELECT r.organization_id, r.store_id FROM public.ai_budget_reservation r WHERE r.id = p_reservation_id
$$;

-- The calls whose cost is not confirmed, across stores, oldest first: UNKNOWN reservations, and conservative
-- reconciliations not yet corrected (within p_corrections_since). Identifiers and amounts only, no content.
CREATE FUNCTION ai_uncertain_reservations(p_corrections_since timestamptz, p_limit integer)
    RETURNS TABLE (reservation_id uuid, organization_id uuid, store_id uuid, automation_type text, provider text,
                   model text, provider_request_id text, state text, state_reason text, reserved_amount numeric,
                   recorded_cost numeric, currency char(3), sent_at timestamptz, unknown_at timestamptz,
                   settled_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
BEGIN
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'AI_RECONCILIATION_INVALID' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    SELECT r.id, r.organization_id, r.store_id, r.automation_type, p.provider, p.model, r.provider_request_id,
           CASE WHEN r.state = 'UNKNOWN' THEN 'UNKNOWN' ELSE 'RECONCILED_CONSERVATIVE' END,
           r.state_reason, r.reserved_amount, r.settled_amount, r.currency, r.sent_at, r.unknown_at, r.settled_at
    FROM public.ai_budget_reservation r
    JOIN public.ai_price p ON p.id = r.price_id
    LEFT JOIN public.ai_usage_event e ON e.reservation_id = r.id
    WHERE r.state = 'UNKNOWN'
       OR (r.state = 'RECONCILED' AND e.outcome = 'RECONCILED_CONSERVATIVE' AND r.settled_at >= p_corrections_since
           AND NOT EXISTS (SELECT 1 FROM public.ai_usage_adjustment a WHERE a.reservation_id = r.id))
    ORDER BY coalesce(r.unknown_at, r.sent_at, r.created_at), r.id
    LIMIT p_limit;
END
$$;

-- Corrects a conservative reconciliation of the bound store once the provider's data is known: NOT_CHARGED (the
-- provider has no charge for the call) or CHARGED with the confirmed amount. The usage ledger row stays; the
-- adjustment records the difference, and every balance the reservation charged moves by it. Repeating the same
-- correction returns DUPLICATE; a different second one is a conflict.
CREATE FUNCTION ai_adjust_reconciliation(p_reservation_id uuid, p_outcome text, p_cost numeric, p_recorded_by text,
                                         p_reason text)
    RETURNS TABLE (outcome text, cost numeric, delta numeric)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_reservation public.ai_budget_reservation%ROWTYPE;
    v_event public.ai_usage_event%ROWTYPE;
    v_existing public.ai_usage_adjustment%ROWTYPE;
    v_cost numeric;
    v_delta numeric;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('NOT_CHARGED', 'CHARGED')
       OR (p_outcome = 'CHARGED' AND (p_cost IS NULL OR p_cost < 0))
       OR (p_outcome = 'NOT_CHARGED' AND p_cost IS NOT NULL)
       OR p_recorded_by IS NULL OR length(p_recorded_by) NOT BETWEEN 1 AND 200
       OR p_reason IS NULL OR length(p_reason) NOT BETWEEN 1 AND 500 THEN
        RAISE EXCEPTION 'AI_RECONCILIATION_INVALID' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_reservation FROM public.ai_budget_reservation r
    WHERE r.id = p_reservation_id AND r.organization_id = v_org AND r.store_id = v_store
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    v_cost := CASE p_outcome WHEN 'NOT_CHARGED' THEN 0 ELSE ceil(p_cost * 100000000) / 100000000 END;
    SELECT * INTO v_existing FROM public.ai_usage_adjustment a WHERE a.reservation_id = p_reservation_id;
    IF FOUND THEN
        IF v_existing.outcome = 'CORRECTED_' || p_outcome AND v_existing.corrected_cost = v_cost THEN
            RETURN QUERY SELECT 'DUPLICATE'::text, v_existing.corrected_cost, v_existing.delta;
            RETURN;
        END IF;
        RAISE EXCEPTION 'AI_SETTLEMENT_CONFLICT: ADJUSTED' USING ERRCODE = '55000';
    END IF;
    SELECT * INTO v_event FROM public.ai_usage_event e WHERE e.reservation_id = p_reservation_id;
    IF v_reservation.state <> 'RECONCILED' OR NOT FOUND OR v_event.outcome <> 'RECONCILED_CONSERVATIVE' THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_ADJUSTABLE: %', v_reservation.state USING ERRCODE = '55000';
    END IF;
    v_delta := v_cost - v_reservation.settled_amount;
    PERFORM 1 FROM public.ai_budget_balance b
    JOIN public.ai_budget_charge c ON c.balance_id = b.id
    WHERE c.reservation_id = p_reservation_id
    ORDER BY CASE b.level WHEN 'PLATFORM' THEN 1 WHEN 'ORGANIZATION' THEN 2 WHEN 'STORE' THEN 3 ELSE 4 END,
             b.period_type, b.scope_key
    FOR UPDATE OF b;
    UPDATE public.ai_budget_balance b
    SET spent_amount = b.spent_amount + v_delta, version = b.version + 1, updated_at = now()
    FROM public.ai_budget_charge c
    WHERE c.reservation_id = p_reservation_id AND c.balance_id = b.id;
    UPDATE public.ai_budget_reservation
    SET settled_amount = v_cost, overrun_amount = greatest(v_cost - reserved_amount, 0),
        state_reason = left('CORRECTED_' || p_outcome || ': ' || p_reason, 500), updated_at = now()
    WHERE id = p_reservation_id;
    INSERT INTO public.ai_usage_adjustment (id, organization_id, store_id, reservation_id, usage_event_id, outcome,
                                            currency, previous_cost, corrected_cost, delta, recorded_by, reason)
    VALUES (gen_random_uuid(), v_org, v_store, p_reservation_id, v_event.id, 'CORRECTED_' || p_outcome,
            v_reservation.currency, v_reservation.settled_amount, v_cost, v_delta, p_recorded_by, p_reason);
    RETURN QUERY SELECT ('CORRECTED_' || p_outcome)::text, v_cost, v_delta;
END
$$;

REVOKE EXECUTE ON FUNCTION ai_mark_unknown(uuid, text, text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_reservation_location(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_uncertain_reservations(timestamptz, integer) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_adjust_reconciliation(uuid, text, numeric, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_mark_unknown(uuid, text, text) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_reservation_location(uuid) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_uncertain_reservations(timestamptz, integer) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_adjust_reconciliation(uuid, text, numeric, text, text) TO operator_runtime;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
