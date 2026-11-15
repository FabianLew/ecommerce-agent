-- ADR-0027 stage 1, step 2: settlement, uncertain outcomes and the usage ledger.
--
-- Reservation lifecycle:
--   RESERVED     reserved, the call is known not to have been sent; it may be released.
--   IN_PROGRESS  ai_mark_sent let exactly one caller send it; from here on the provider may have charged it.
--   SETTLED      the provider's usage is known and the actual cost is recorded in full.
--   RELEASED     never sent; the whole reservation went back to the budgets.
--   UNKNOWN      sent (or possibly sent) and the result is unknown: the reservation keeps counting.
--   RECONCILED   an UNKNOWN reservation was closed later: not charged, charged a known amount, or conservatively at
--                the full reserved amount.
-- Nothing assumes exactly-once delivery: every transition is a conditional update of one locked row, so a repeated
-- or parallel command (a Temporal activity retry, a second worker) changes nothing the second time.

ALTER TABLE ai_budget_reservation
    ADD COLUMN sent_at        timestamptz,
    ADD COLUMN unknown_at     timestamptz,
    ADD COLUMN overrun_amount numeric(20, 8) CHECK (overrun_amount >= 0);
CREATE INDEX ai_budget_reservation_unknown ON ai_budget_reservation (unknown_at) WHERE state = 'UNKNOWN';

-- One row per closed reservation that may have cost money: what the provider used and what it cost, at the price
-- the reservation was made with. No prompt or response content is ever stored here (ADR-0027 section 11).
CREATE TABLE ai_usage_event (
    id                 uuid PRIMARY KEY,
    organization_id    uuid           NOT NULL,
    store_id           uuid           NOT NULL,
    reservation_id     uuid           NOT NULL UNIQUE REFERENCES ai_budget_reservation (id),
    automation_type    text           NOT NULL,
    agent_run_id       uuid,
    price_id           uuid           NOT NULL REFERENCES ai_price (id),
    provider           text           NOT NULL,
    model              text           NOT NULL,
    region             text           NOT NULL,
    price_version      text           NOT NULL,
    currency           char(3)        NOT NULL,
    provider_call_id   text CHECK (length(provider_call_id) BETWEEN 1 AND 200),
    input_tokens       bigint CHECK (input_tokens >= 0),
    output_tokens      bigint CHECK (output_tokens >= 0),
    cache_write_tokens bigint CHECK (cache_write_tokens >= 0),
    cache_read_tokens  bigint CHECK (cache_read_tokens >= 0),
    extra_units        jsonb          NOT NULL DEFAULT '{}'::jsonb,
    cost               numeric(20, 8) NOT NULL CHECK (cost >= 0),
    reserved_amount    numeric(20, 8) NOT NULL CHECK (reserved_amount > 0),
    -- The estimate-to-bill difference: how far the actual cost went above the reservation (never capped).
    overrun_amount     numeric(20, 8) NOT NULL CHECK (overrun_amount >= 0),
    outcome            text           NOT NULL CHECK (outcome IN ('SETTLED', 'RECONCILED_NOT_CHARGED',
                                                                  'RECONCILED_CHARGED', 'RECONCILED_CONSERVATIVE')),
    recorded_by        text           NOT NULL CHECK (length(recorded_by) BETWEEN 1 AND 200),
    reason             text CHECK (length(reason) <= 500),
    created_at         timestamptz    NOT NULL DEFAULT now(),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (agent_run_id, store_id) REFERENCES agent_run (id, store_id)
);
CREATE INDEX ai_usage_event_store_time ON ai_usage_event (organization_id, store_id, created_at);

CREATE FUNCTION ai_usage_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ai_usage_event rows are append-only (% not allowed)', TG_OP;
END
$$;
CREATE TRIGGER ai_usage_event_append_only BEFORE UPDATE OR DELETE ON ai_usage_event
    FOR EACH ROW EXECUTE FUNCTION ai_usage_event_is_append_only();

-- A store reads its own usage; nobody writes it except the reviewed functions below.
ALTER TABLE ai_usage_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_usage_event FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_usage_event FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
GRANT SELECT ON ai_usage_event TO operator_runtime;
GRANT SELECT, INSERT ON ai_usage_event TO operator_system;
CREATE POLICY governance ON ai_usage_event TO operator_system USING (true) WITH CHECK (true);

GRANT CREATE ON SCHEMA public TO operator_system;

-- The actual cost of a call from its usage, at the reservation's price, rounded up to 8 decimals. Cache reads
-- without a cache-read price are charged at the input price (a cache read never costs more than plain input);
-- cache writes or extra units without a price cannot be costed and are refused, so the reservation stays open and
-- is reconciled rather than recorded as free.
CREATE FUNCTION ai_usage_cost(p_price public.ai_price, p_input bigint, p_output bigint, p_cache_write bigint,
                              p_cache_read bigint, p_extra jsonb)
    RETURNS numeric
    LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog, public AS
$$
DECLARE
    v_cost numeric := 0;
    v_unit record;
BEGIN
    IF p_input < 0 OR p_output < 0 OR p_cache_write < 0 OR p_cache_read < 0 THEN
        RAISE EXCEPTION 'AI_USAGE_INVALID' USING ERRCODE = '22023';
    END IF;
    IF p_cache_write > 0 AND p_price.cache_write_per_mtok IS NULL THEN
        RAISE EXCEPTION 'AI_USAGE_NOT_PRICED: cache_write' USING ERRCODE = '22023';
    END IF;
    v_cost := (p_input * p_price.input_per_mtok + p_output * p_price.output_per_mtok
               + p_cache_write * coalesce(p_price.cache_write_per_mtok, 0)
               + p_cache_read * coalesce(p_price.cache_read_per_mtok, p_price.input_per_mtok)) / 1000000;
    FOR v_unit IN SELECT key, value FROM jsonb_each(coalesce(p_extra, '{}'::jsonb)) LOOP
        IF jsonb_typeof(v_unit.value) <> 'number' OR v_unit.value::numeric < 0 THEN
            RAISE EXCEPTION 'AI_USAGE_INVALID' USING ERRCODE = '22023';
        END IF;
        IF v_unit.value::numeric > 0 AND NOT (p_price.extras ? v_unit.key) THEN
            RAISE EXCEPTION 'AI_USAGE_NOT_PRICED: %', v_unit.key USING ERRCODE = '22023';
        END IF;
        IF v_unit.value::numeric > 0 THEN
            v_cost := v_cost + v_unit.value::numeric * (p_price.extras ->> v_unit.key)::numeric;
        END IF;
    END LOOP;
    RETURN ceil(v_cost * 100000000) / 100000000;
END
$$;
ALTER FUNCTION ai_usage_cost(public.ai_price, bigint, bigint, bigint, bigint, jsonb) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_usage_cost(public.ai_price, bigint, bigint, bigint, bigint, jsonb) FROM PUBLIC;

-- Closes one locked reservation: every balance it charged gives back its reservation and is charged the actual cost
-- in full (an overrun is recorded, never capped). Balances are locked in the same fixed order as ai_reserve. Not a
-- definer function and not executable by the runtime: only the definer functions below call it.
CREATE FUNCTION ai_close_reservation(p_reservation_id uuid, p_cost numeric, p_state text, p_reason text)
    RETURNS numeric
    LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS
$$
DECLARE
    v_reserved numeric;
    v_overrun numeric;
BEGIN
    SELECT reserved_amount INTO STRICT v_reserved FROM public.ai_budget_reservation WHERE id = p_reservation_id;
    v_overrun := greatest(p_cost - v_reserved, 0);
    PERFORM 1 FROM public.ai_budget_balance b
    JOIN public.ai_budget_charge c ON c.balance_id = b.id
    WHERE c.reservation_id = p_reservation_id
    ORDER BY CASE b.level WHEN 'PLATFORM' THEN 1 WHEN 'ORGANIZATION' THEN 2 WHEN 'STORE' THEN 3 ELSE 4 END,
             b.period_type, b.scope_key
    FOR UPDATE OF b;
    UPDATE public.ai_budget_balance b
    SET reserved_amount = b.reserved_amount - c.amount, spent_amount = b.spent_amount + p_cost,
        version = b.version + 1, updated_at = now()
    FROM public.ai_budget_charge c
    WHERE c.reservation_id = p_reservation_id AND c.balance_id = b.id;
    UPDATE public.ai_budget_reservation
    SET state = p_state, state_reason = p_reason, updated_at = now(),
        settled_amount = CASE WHEN p_state = 'RELEASED' THEN NULL ELSE p_cost END,
        overrun_amount = CASE WHEN p_state = 'RELEASED' THEN NULL ELSE v_overrun END,
        settled_at = CASE WHEN p_state = 'RELEASED' THEN NULL ELSE now() END
    WHERE id = p_reservation_id;
    RETURN v_overrun;
END
$$;
ALTER FUNCTION ai_close_reservation(uuid, numeric, text, text) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_close_reservation(uuid, numeric, text, text) FROM PUBLIC;

-- Lets exactly one caller send the call of a reservation of the bound store. It must be called, and committed,
-- before the request leaves: a crash before it leaves the reservation RESERVED (known not sent, released when it
-- expires), a crash after it leaves it IN_PROGRESS (possibly sent, UNKNOWN when it expires). An expired
-- reservation is never sent. A repeated attempt (activity retry) gets ALREADY_ATTEMPTED and must not send again.
CREATE FUNCTION ai_mark_sent(p_reservation_id uuid)
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
    UPDATE public.ai_budget_reservation
    SET state = 'IN_PROGRESS', sent_at = now(), updated_at = now()
    WHERE id = p_reservation_id;
    RETURN 'SEND';
END
$$;
ALTER FUNCTION ai_mark_sent(uuid) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_mark_sent(uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_mark_sent(uuid) TO operator_runtime;

-- Records the provider's usage for a call that was sent (IN_PROGRESS, or UNKNOWN when the answer came late). The
-- cost is computed here from the reservation's own price, so it cannot be understated by the caller. Settling the
-- same usage again returns DUPLICATE and charges nothing; different usage for a closed reservation is a conflict.
CREATE FUNCTION ai_settle(p_reservation_id uuid, p_provider_call_id text, p_input_tokens bigint,
                          p_output_tokens bigint, p_cache_write_tokens bigint, p_cache_read_tokens bigint,
                          p_extra_units jsonb)
    RETURNS TABLE (outcome text, cost numeric, overrun numeric)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_reservation public.ai_budget_reservation%ROWTYPE;
    v_price public.ai_price%ROWTYPE;
    v_event public.ai_usage_event%ROWTYPE;
    v_extra jsonb := coalesce(p_extra_units, '{}'::jsonb);
    v_cost numeric;
    v_overrun numeric;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_input_tokens IS NULL OR p_output_tokens IS NULL OR p_cache_write_tokens IS NULL
       OR p_cache_read_tokens IS NULL OR jsonb_typeof(v_extra) <> 'object'
       OR (p_provider_call_id IS NOT NULL AND length(p_provider_call_id) NOT BETWEEN 1 AND 200) THEN
        RAISE EXCEPTION 'AI_USAGE_INVALID' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_reservation FROM public.ai_budget_reservation r
    WHERE r.id = p_reservation_id AND r.organization_id = v_org AND r.store_id = v_store
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    IF v_reservation.state IN ('SETTLED', 'RECONCILED') THEN
        SELECT * INTO v_event FROM public.ai_usage_event e WHERE e.reservation_id = p_reservation_id;
        IF v_event.outcome = 'SETTLED'
           AND v_event.provider_call_id IS NOT DISTINCT FROM p_provider_call_id
           AND v_event.input_tokens = p_input_tokens AND v_event.output_tokens = p_output_tokens
           AND v_event.cache_write_tokens = p_cache_write_tokens AND v_event.cache_read_tokens = p_cache_read_tokens
           AND v_event.extra_units = v_extra THEN
            RETURN QUERY SELECT 'DUPLICATE'::text, v_event.cost, v_event.overrun_amount;
            RETURN;
        END IF;
        RAISE EXCEPTION 'AI_SETTLEMENT_CONFLICT: %', v_reservation.state USING ERRCODE = '55000';
    END IF;
    IF v_reservation.state NOT IN ('IN_PROGRESS', 'UNKNOWN') THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_SETTLEABLE: %', v_reservation.state USING ERRCODE = '55000';
    END IF;

    SELECT * INTO STRICT v_price FROM public.ai_price p WHERE p.id = v_reservation.price_id;
    v_cost := public.ai_usage_cost(v_price, p_input_tokens, p_output_tokens, p_cache_write_tokens,
                                   p_cache_read_tokens, v_extra);
    v_overrun := public.ai_close_reservation(p_reservation_id, v_cost, 'SETTLED',
                                             CASE WHEN v_reservation.state = 'UNKNOWN'
                                                  THEN 'LATE_PROVIDER_RESULT' END);
    INSERT INTO public.ai_usage_event (id, organization_id, store_id, reservation_id, automation_type, agent_run_id,
                                       price_id, provider, model, region, price_version, currency, provider_call_id,
                                       input_tokens, output_tokens, cache_write_tokens, cache_read_tokens,
                                       extra_units, cost, reserved_amount, overrun_amount, outcome, recorded_by)
    VALUES (gen_random_uuid(), v_org, v_store, p_reservation_id, v_reservation.automation_type,
            v_reservation.agent_run_id, v_price.id, v_price.provider, v_price.model, v_price.region,
            v_price.price_version, v_reservation.currency, p_provider_call_id, p_input_tokens, p_output_tokens,
            p_cache_write_tokens, p_cache_read_tokens, v_extra, v_cost, v_reservation.reserved_amount, v_overrun,
            'SETTLED', 'PROVIDER_USAGE');
    RETURN QUERY SELECT 'SETTLED'::text, v_cost, v_overrun;
END
$$;
ALTER FUNCTION ai_settle(uuid, text, bigint, bigint, bigint, bigint, jsonb) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_settle(uuid, text, bigint, bigint, bigint, bigint, jsonb) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_settle(uuid, text, bigint, bigint, bigint, bigint, jsonb) TO operator_runtime;

-- A sent call whose result is unknown (timeout, dropped connection, unreadable answer). The reservation keeps
-- counting against every budget until it is settled or reconciled. Repeating it changes nothing.
CREATE FUNCTION ai_mark_unknown(p_reservation_id uuid, p_reason text)
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
    IF p_reason IS NULL OR length(p_reason) NOT BETWEEN 1 AND 500 THEN
        RAISE EXCEPTION 'AI_RESERVATION_INVALID' USING ERRCODE = '22023';
    END IF;
    SELECT * INTO v_reservation FROM public.ai_budget_reservation r
    WHERE r.id = p_reservation_id AND r.organization_id = v_org AND r.store_id = v_store
    FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_FOUND' USING ERRCODE = 'P0002';
    END IF;
    IF v_reservation.state = 'RESERVED' THEN
        -- Never sent: nothing is uncertain, the caller releases it instead.
        RAISE EXCEPTION 'AI_RESERVATION_NOT_SENT' USING ERRCODE = '55000';
    END IF;
    IF v_reservation.state = 'IN_PROGRESS' THEN
        UPDATE public.ai_budget_reservation
        SET state = 'UNKNOWN', state_reason = p_reason, unknown_at = now(), updated_at = now()
        WHERE id = p_reservation_id;
        RETURN 'UNKNOWN';
    END IF;
    -- Already UNKNOWN, or closed by a result that arrived first: keep what is there.
    RETURN v_reservation.state;
END
$$;
ALTER FUNCTION ai_mark_unknown(uuid, text) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_mark_unknown(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_mark_unknown(uuid, text) TO operator_runtime;

-- Closes an UNKNOWN reservation of the bound store once its outcome is decided, by a person or by the automatic
-- conservative rule: NOT_CHARGED (confirmed not charged), CHARGED (a confirmed amount, recorded in full even above
-- the reservation) or CONSERVATIVE (the full reserved amount). The decision is recorded with who made it.
CREATE FUNCTION ai_reconcile(p_reservation_id uuid, p_outcome text, p_cost numeric, p_recorded_by text,
                             p_reason text)
    RETURNS TABLE (outcome text, cost numeric, overrun numeric)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_reservation public.ai_budget_reservation%ROWTYPE;
    v_event public.ai_usage_event%ROWTYPE;
    v_cost numeric;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_outcome IS NULL OR p_outcome NOT IN ('NOT_CHARGED', 'CHARGED', 'CONSERVATIVE')
       OR (p_outcome = 'CHARGED' AND (p_cost IS NULL OR p_cost < 0))
       OR (p_outcome <> 'CHARGED' AND p_cost IS NOT NULL)
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
    v_cost := CASE p_outcome WHEN 'NOT_CHARGED' THEN 0 WHEN 'CONSERVATIVE' THEN v_reservation.reserved_amount
                             ELSE ceil(p_cost * 100000000) / 100000000 END;
    IF v_reservation.state = 'RECONCILED' THEN
        SELECT * INTO v_event FROM public.ai_usage_event e WHERE e.reservation_id = p_reservation_id;
        IF v_event.outcome = 'RECONCILED_' || p_outcome AND v_event.cost = v_cost THEN
            RETURN QUERY SELECT 'DUPLICATE'::text, v_event.cost, v_event.overrun_amount;
            RETURN;
        END IF;
        RAISE EXCEPTION 'AI_SETTLEMENT_CONFLICT: RECONCILED' USING ERRCODE = '55000';
    END IF;
    IF v_reservation.state <> 'UNKNOWN' THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_RECONCILABLE: %', v_reservation.state USING ERRCODE = '55000';
    END IF;
    RETURN QUERY SELECT * FROM public.ai_record_reconciliation(v_reservation, p_outcome, v_cost, p_recorded_by,
                                                               p_reason);
END
$$;

-- The shared write of a reconciliation; called by ai_reconcile and by the sweep, never by the runtime.
CREATE FUNCTION ai_record_reconciliation(p_reservation public.ai_budget_reservation, p_outcome text,
                                         p_cost numeric, p_recorded_by text, p_reason text)
    RETURNS TABLE (outcome text, cost numeric, overrun numeric)
    LANGUAGE plpgsql VOLATILE SET search_path = pg_catalog, public AS
$$
DECLARE
    v_price public.ai_price%ROWTYPE;
    v_overrun numeric;
BEGIN
    SELECT * INTO STRICT v_price FROM public.ai_price p WHERE p.id = p_reservation.price_id;
    v_overrun := public.ai_close_reservation(p_reservation.id, p_cost, 'RECONCILED', p_outcome || ': ' || p_reason);
    INSERT INTO public.ai_usage_event (id, organization_id, store_id, reservation_id, automation_type, agent_run_id,
                                       price_id, provider, model, region, price_version, currency, cost,
                                       reserved_amount, overrun_amount, outcome, recorded_by, reason)
    VALUES (gen_random_uuid(), p_reservation.organization_id, p_reservation.store_id, p_reservation.id,
            p_reservation.automation_type, p_reservation.agent_run_id, v_price.id, v_price.provider, v_price.model,
            v_price.region, v_price.price_version, p_reservation.currency, p_cost, p_reservation.reserved_amount,
            v_overrun, 'RECONCILED_' || p_outcome, p_recorded_by, p_reason);
    RETURN QUERY SELECT ('RECONCILED_' || p_outcome)::text, p_cost, v_overrun;
END
$$;
ALTER FUNCTION ai_record_reconciliation(public.ai_budget_reservation, text, numeric, text, text)
    OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_record_reconciliation(public.ai_budget_reservation, text, numeric, text, text)
    FROM PUBLIC;
ALTER FUNCTION ai_reconcile(uuid, text, numeric, text, text) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_reconcile(uuid, text, numeric, text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_reconcile(uuid, text, numeric, text, text) TO operator_runtime;

-- The periodic sweep over all stores, so no reservation blocks a budget invisibly or for ever:
--   RESERVED past its expiry   -> RELEASED (never sent: ai_mark_sent refuses an expired reservation);
--   IN_PROGRESS past its expiry -> UNKNOWN (the worker may have crashed after sending);
--   UNKNOWN for longer than p_conservative_after -> RECONCILED conservatively at the full reserved amount, when
--   p_conservative_after is given; without it UNKNOWN waits for a person and stays visible in the metrics.
-- Rows another transaction holds are skipped and taken by the next run.
CREATE FUNCTION ai_sweep_reservations(p_conservative_after interval, p_batch_size integer)
    RETURNS TABLE (released_count integer, unknown_count integer, reconciled_count integer)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_reservation public.ai_budget_reservation%ROWTYPE;
BEGIN
    IF p_batch_size IS NULL OR p_batch_size NOT BETWEEN 1 AND 10000
       OR (p_conservative_after IS NOT NULL AND p_conservative_after < interval '1 minute') THEN
        RAISE EXCEPTION 'AI_SWEEP_INVALID' USING ERRCODE = '22023';
    END IF;
    released_count := 0;
    unknown_count := 0;
    reconciled_count := 0;
    FOR v_reservation IN
        SELECT * FROM public.ai_budget_reservation r
        WHERE r.state = 'RESERVED' AND r.expires_at <= now()
        ORDER BY r.expires_at LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    LOOP
        PERFORM public.ai_close_reservation(v_reservation.id, 0, 'RELEASED', 'EXPIRED_NOT_SENT');
        released_count := released_count + 1;
    END LOOP;
    WITH overdue AS (
        SELECT r.id FROM public.ai_budget_reservation r
        WHERE r.state = 'IN_PROGRESS' AND r.expires_at <= now()
        ORDER BY r.expires_at LIMIT p_batch_size
        FOR UPDATE SKIP LOCKED
    )
    UPDATE public.ai_budget_reservation r
    SET state = 'UNKNOWN', state_reason = 'TIMED_OUT_IN_PROGRESS', unknown_at = now(), updated_at = now()
    FROM overdue WHERE r.id = overdue.id;
    GET DIAGNOSTICS unknown_count = ROW_COUNT;
    IF p_conservative_after IS NOT NULL THEN
        FOR v_reservation IN
            SELECT * FROM public.ai_budget_reservation r
            WHERE r.state = 'UNKNOWN' AND r.unknown_at <= now() - p_conservative_after
            ORDER BY r.unknown_at LIMIT p_batch_size
            FOR UPDATE SKIP LOCKED
        LOOP
            PERFORM public.ai_record_reconciliation(v_reservation, 'CONSERVATIVE', v_reservation.reserved_amount,
                                                    'SYSTEM:ai-reservation-sweep',
                                                    'No provider result within ' || p_conservative_after::text);
            reconciled_count := reconciled_count + 1;
        END LOOP;
    END IF;
    RETURN NEXT;
END
$$;
ALTER FUNCTION ai_sweep_reservations(interval, integer) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_sweep_reservations(interval, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_sweep_reservations(interval, integer) TO operator_runtime;

-- The operational gauges (ADR-0024) gain the AI reservation signals; the existing metrics are unchanged.
DROP FUNCTION operational_metric(text, integer);
CREATE FUNCTION operational_metric(p_name text, p_max_attempts integer DEFAULT NULL)
    RETURNS double precision
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v double precision;
BEGIN
    CASE p_name
        WHEN 'operator.executions.unknown' THEN
            SELECT count(*) INTO v FROM public.action_execution WHERE status = 'UNKNOWN';
        WHEN 'operator.executions.stuck' THEN
            SELECT count(*) INTO v FROM public.action_execution
            WHERE status IN ('IN_PROGRESS', 'APPLIED') AND updated_at < now() - interval '15 minutes';
        WHEN 'operator.mutation.stops.active' THEN
            SELECT count(*) INTO v FROM public.store_mutation_stop WHERE stopped;
        WHEN 'operator.observations.overdue' THEN
            SELECT count(*) INTO v FROM public.outcome_observation
            WHERE status = 'PENDING' AND due_at < now() - interval '1 hour';
        WHEN 'operator.webhook.inbox.pending' THEN
            SELECT count(*) INTO v FROM public.webhook_inbox WHERE status = 'RECEIVED';
        WHEN 'operator.webhook.inbox.oldest.age' THEN
            SELECT extract(epoch FROM now() - min(received_at)) INTO v FROM public.webhook_inbox
            WHERE status = 'RECEIVED';
        WHEN 'operator.outbox.pending' THEN
            SELECT count(*) INTO v FROM public.outbox_event WHERE dispatched_at IS NULL;
        WHEN 'operator.outbox.oldest.age' THEN
            SELECT extract(epoch FROM now() - min(created_at)) INTO v FROM public.outbox_event
            WHERE dispatched_at IS NULL;
        WHEN 'operator.connections.failing' THEN
            SELECT (SELECT count(*) FROM public.integration_connection WHERE status = 'ERROR')
                 + (SELECT count(*) FROM public.meta_connection WHERE status = 'ERROR') INTO v;
        WHEN 'operator.notification.deliveries.exhausted' THEN
            SELECT count(*) INTO v FROM public.notification_delivery WHERE status = 'EXHAUSTED';
        WHEN 'operator.ai.reservations.unknown' THEN
            SELECT count(*) INTO v FROM public.ai_budget_reservation WHERE state = 'UNKNOWN';
        WHEN 'operator.ai.reservations.unknown.oldest.age' THEN
            SELECT extract(epoch FROM now() - min(unknown_at)) INTO v FROM public.ai_budget_reservation
            WHERE state = 'UNKNOWN';
        WHEN 'operator.ai.reservations.overdue' THEN
            SELECT count(*) INTO v FROM public.ai_budget_reservation
            WHERE state IN ('RESERVED', 'IN_PROGRESS') AND expires_at < now() - interval '10 minutes';
        WHEN 'operator.ai.overruns.recent' THEN
            SELECT count(*) INTO v FROM public.ai_usage_event
            WHERE overrun_amount > 0 AND created_at > now() - interval '24 hours';
        ELSE
            RAISE EXCEPTION 'Unknown operational metric %', p_name;
    END CASE;
    RETURN coalesce(v, 0);
END
$$;
ALTER FUNCTION operational_metric(text, integer) OWNER TO operator_system;
REVOKE ALL ON FUNCTION operational_metric(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION operational_metric(text, integer) TO operator_runtime;

REVOKE CREATE ON SCHEMA public FROM operator_system;

-- Functions are created with EXECUTE for PUBLIC: the per-schema default in V25 cannot take away a global default,
-- and REVOKE and GRANT statements run by the migrator after the owner changed take no effect, because the
-- migrator holds no grant option of its own. Until here the runtime could call the definer functions only through
-- PUBLIC. So every function owned by operator_system is fixed as its owner: PUBLIC loses EXECUTE, the runtime gets it
-- explicitly on the reviewed definer functions only, and the internal helpers above stay callable by their owner
-- alone. The global default of the migrator is fixed as well, so later functions start without PUBLIC.
SET ROLE operator_system;
DO
$$
DECLARE
    v_function regprocedure;
BEGIN
    FOR v_function IN
        SELECT p.oid::regprocedure FROM pg_proc p
        WHERE p.pronamespace = 'public'::regnamespace AND p.proowner = 'operator_system'::regrole
    LOOP
        EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC', v_function);
    END LOOP;
    FOR v_function IN
        SELECT p.oid::regprocedure FROM pg_proc p
        WHERE p.pronamespace = 'public'::regnamespace AND p.proowner = 'operator_system'::regrole AND p.prosecdef
    LOOP
        EXECUTE format('GRANT EXECUTE ON FUNCTION %s TO operator_runtime', v_function);
    END LOOP;
END
$$;
RESET ROLE;
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
