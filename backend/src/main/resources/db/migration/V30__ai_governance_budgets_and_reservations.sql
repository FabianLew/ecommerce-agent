-- ADR-0027 stage 1, step 1 (E-53): the AI pricing catalog, budget policies and balances, and atomic cost
-- reservations. On the pilot branch: renumber when it is merged into main.
--
-- Money is NUMERIC, never floating point. Budgets above a single store (platform, organization) are not store data
-- the runtime may read: every budget table is reachable only through the reviewed SECURITY DEFINER functions below,
-- which take the store from the bound scope (app.organization_id, app.store_id), never from an argument.

-- The versioned price catalog. A price change is a new row (a new price_version); rows are never updated or deleted,
-- so historical costs keep the price they were reserved and settled with.
CREATE TABLE ai_price (
    id                     uuid PRIMARY KEY,
    provider               text           NOT NULL CHECK (provider ~ '^[a-z0-9-]{1,40}$'),
    model                  text           NOT NULL CHECK (length(model) BETWEEN 1 AND 120),
    -- Region or inference profile; 'global' for a provider API without a regional price.
    region                 text           NOT NULL CHECK (length(region) BETWEEN 1 AND 60),
    price_version          text           NOT NULL CHECK (length(price_version) BETWEEN 1 AND 60),
    currency               char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    -- Prices per million tokens.
    input_per_mtok         numeric(20, 8) NOT NULL CHECK (input_per_mtok >= 0),
    output_per_mtok        numeric(20, 8) NOT NULL CHECK (output_per_mtok >= 0),
    cache_write_per_mtok   numeric(20, 8) CHECK (cache_write_per_mtok >= 0),
    cache_read_per_mtok    numeric(20, 8) CHECK (cache_read_per_mtok >= 0),
    -- Additional paid components, unit name -> price per unit (e.g. {"web_search": 0.01}).
    extras                 jsonb          NOT NULL DEFAULT '{}'::jsonb CHECK (jsonb_typeof(extras) = 'object'),
    effective_from         timestamptz    NOT NULL,
    source                 text           NOT NULL CHECK (length(source) BETWEEN 1 AND 500),
    -- Null until someone checked the price against the provider; an unverified price is never used.
    verified_at            timestamptz,
    created_at             timestamptz    NOT NULL DEFAULT now(),
    UNIQUE (provider, model, region, price_version)
);
CREATE INDEX ai_price_current ON ai_price (provider, model, region, effective_from DESC);

-- Budget policies. A change is a new row that supersedes the active one; history is kept.
CREATE TABLE ai_budget_policy (
    id                     uuid PRIMARY KEY,
    level                  text           NOT NULL CHECK (level IN ('PLATFORM', 'ORGANIZATION', 'STORE', 'AUTOMATION')),
    organization_id        uuid REFERENCES organization (id),
    store_id               uuid,
    automation_type        text CHECK (automation_type ~ '^[a-z0-9-]{1,64}$'),
    period_type            text           NOT NULL CHECK (period_type IN ('MONTHLY', 'DAILY')),
    amount_limit           numeric(20, 8) NOT NULL CHECK (amount_limit >= 0),
    currency               char(3)        NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    policy_version         integer        NOT NULL CHECK (policy_version >= 1),
    reason                 text           NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    created_by             text           NOT NULL CHECK (length(created_by) BETWEEN 1 AND 200),
    created_at             timestamptz    NOT NULL DEFAULT now(),
    superseded_at          timestamptz,
    scope_key              text GENERATED ALWAYS AS (
                               level || ':' || coalesce(organization_id::text, '-') || ':'
                               || coalesce(store_id::text, '-') || ':' || coalesce(automation_type, '-')) STORED,
    CHECK ((level = 'PLATFORM' AND organization_id IS NULL AND store_id IS NULL AND automation_type IS NULL)
        OR (level = 'ORGANIZATION' AND organization_id IS NOT NULL AND store_id IS NULL AND automation_type IS NULL)
        OR (level = 'STORE' AND organization_id IS NOT NULL AND store_id IS NOT NULL AND automation_type IS NULL)
        OR (level = 'AUTOMATION' AND organization_id IS NOT NULL AND store_id IS NOT NULL
            AND automation_type IS NOT NULL)),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    UNIQUE (scope_key, period_type, policy_version)
);
CREATE UNIQUE INDEX ai_budget_policy_active ON ai_budget_policy (scope_key, period_type) WHERE superseded_at IS NULL;

-- A policy row is immutable except for being superseded once.
CREATE FUNCTION ai_budget_policy_is_immutable() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- scope_key is generated, so it is not yet computed in NEW inside a BEFORE trigger.
    IF TG_OP = 'UPDATE' AND OLD.superseded_at IS NULL AND NEW.superseded_at IS NOT NULL
       AND (to_jsonb(NEW) - 'superseded_at' - 'scope_key') = (to_jsonb(OLD) - 'superseded_at' - 'scope_key') THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'ai_budget_policy rows are immutable (% not allowed)', TG_OP;
END
$$;
CREATE TRIGGER ai_budget_policy_immutable BEFORE UPDATE OR DELETE ON ai_budget_policy
    FOR EACH ROW EXECUTE FUNCTION ai_budget_policy_is_immutable();

CREATE FUNCTION ai_price_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    -- Verifying a price records when it was checked; nothing else about a price row ever changes.
    IF TG_OP = 'UPDATE' AND (to_jsonb(NEW) - 'verified_at') = (to_jsonb(OLD) - 'verified_at')
       AND NEW.verified_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'ai_price rows are append-only (% not allowed)', TG_OP;
END
$$;
CREATE TRIGGER ai_price_append_only BEFORE UPDATE OR DELETE ON ai_price
    FOR EACH ROW EXECUTE FUNCTION ai_price_is_append_only();

-- Spent and reserved amounts per budget scope and period window. Keyed by scope and window, not by policy version,
-- so a policy change in the middle of a period keeps what was already spent.
CREATE TABLE ai_budget_balance (
    id                     uuid PRIMARY KEY,
    level                  text           NOT NULL CHECK (level IN ('PLATFORM', 'ORGANIZATION', 'STORE', 'AUTOMATION')),
    scope_key              text           NOT NULL,
    organization_id        uuid,
    store_id               uuid,
    period_type            text           NOT NULL CHECK (period_type IN ('MONTHLY', 'DAILY')),
    period_start           timestamptz    NOT NULL,
    period_end             timestamptz    NOT NULL CHECK (period_end > period_start),
    currency               char(3)        NOT NULL,
    reserved_amount        numeric(20, 8) NOT NULL DEFAULT 0 CHECK (reserved_amount >= 0),
    spent_amount           numeric(20, 8) NOT NULL DEFAULT 0 CHECK (spent_amount >= 0),
    version                bigint         NOT NULL DEFAULT 0,
    updated_at             timestamptz    NOT NULL DEFAULT now(),
    -- Platform and organization balances have no store; the key is checked only where there is one.
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    UNIQUE (scope_key, period_type, period_start)
);

-- One reservation per paid model call. States follow ADR-0027 section 4.5; this step uses RESERVED and RELEASED.
CREATE TABLE ai_budget_reservation (
    id                     uuid PRIMARY KEY,
    organization_id        uuid           NOT NULL,
    store_id               uuid           NOT NULL,
    automation_type        text           NOT NULL CHECK (automation_type ~ '^[a-z0-9-]{1,64}$'),
    agent_run_id           uuid,
    idempotency_key        text           NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    price_id               uuid           NOT NULL REFERENCES ai_price (id),
    max_input_tokens       integer        NOT NULL CHECK (max_input_tokens > 0),
    max_output_tokens      integer        NOT NULL CHECK (max_output_tokens > 0),
    estimated_max_cost     numeric(20, 8) NOT NULL CHECK (estimated_max_cost > 0),
    reserved_amount        numeric(20, 8) NOT NULL CHECK (reserved_amount > 0),
    settled_amount         numeric(20, 8) CHECK (settled_amount >= 0),
    currency               char(3)        NOT NULL,
    state                  text           NOT NULL
        CHECK (state IN ('RESERVED', 'IN_PROGRESS', 'SETTLED', 'RELEASED', 'UNKNOWN', 'RECONCILED')),
    state_reason           text CHECK (length(state_reason) <= 500),
    created_at             timestamptz    NOT NULL DEFAULT now(),
    -- After this time a reservation that is still open is overdue: never silently dropped, always visible.
    expires_at             timestamptz    NOT NULL,
    updated_at             timestamptz    NOT NULL DEFAULT now(),
    settled_at             timestamptz,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (agent_run_id, store_id) REFERENCES agent_run (id, store_id),
    UNIQUE (organization_id, store_id, idempotency_key)
);
CREATE INDEX ai_budget_reservation_open ON ai_budget_reservation (expires_at)
    WHERE state IN ('RESERVED', 'IN_PROGRESS', 'UNKNOWN');
CREATE INDEX ai_budget_reservation_run ON ai_budget_reservation (store_id, agent_run_id);

-- Which balances a reservation charged, and how much: release and settlement adjust exactly these rows.
CREATE TABLE ai_budget_charge (
    reservation_id         uuid           NOT NULL REFERENCES ai_budget_reservation (id),
    balance_id             uuid           NOT NULL REFERENCES ai_budget_balance (id),
    organization_id        uuid           NOT NULL,
    store_id               uuid           NOT NULL,
    amount                 numeric(20, 8) NOT NULL CHECK (amount > 0),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    PRIMARY KEY (reservation_id, balance_id)
);

-- Row-level security. The runtime reads its store's reservations and the price catalog; it writes none of these
-- tables directly. Budget policies, balances and charges are invisible to it.
ALTER TABLE ai_price ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_price FORCE ROW LEVEL SECURITY;
CREATE POLICY catalog_read ON ai_price FOR SELECT TO operator_runtime USING (true);

ALTER TABLE ai_budget_policy ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_budget_policy FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_budget_policy TO operator_runtime USING (false) WITH CHECK (false);

ALTER TABLE ai_budget_balance ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_budget_balance FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_budget_balance TO operator_runtime USING (false) WITH CHECK (false);

ALTER TABLE ai_budget_charge ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_budget_charge FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_budget_charge TO operator_runtime USING (false) WITH CHECK (false);

ALTER TABLE ai_budget_reservation ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_budget_reservation FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_budget_reservation FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- The definer functions run as operator_system, which reads and writes these tables only through them.
GRANT SELECT ON ai_price TO operator_system;
GRANT SELECT ON ai_budget_policy TO operator_system;
GRANT SELECT, INSERT, UPDATE ON ai_budget_balance, ai_budget_reservation, ai_budget_charge TO operator_system;
CREATE POLICY governance ON ai_price FOR SELECT TO operator_system USING (true);
CREATE POLICY governance ON ai_budget_policy FOR SELECT TO operator_system USING (true);
CREATE POLICY governance ON ai_budget_balance TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY governance ON ai_budget_reservation TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY governance ON ai_budget_charge TO operator_system USING (true) WITH CHECK (true);
-- Prices and budget policies change only through reviewed migrations (the owner, operator_migrator), which forced
-- row-level security would otherwise lock out too.
CREATE POLICY administration ON ai_price TO operator_migrator USING (true) WITH CHECK (true);
CREATE POLICY administration ON ai_budget_policy TO operator_migrator USING (true) WITH CHECK (true);

-- The budget window that contains a moment, in UTC: a calendar month or a day. Computed by the backend, stored as an
-- explicit interval on the balance.
CREATE FUNCTION ai_budget_window(p_period text, p_at timestamptz, OUT window_start timestamptz,
                                 OUT window_end timestamptz)
    LANGUAGE plpgsql IMMUTABLE AS
$$
BEGIN
    CASE p_period
        WHEN 'MONTHLY' THEN
            window_start := date_trunc('month', p_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
            window_end := (date_trunc('month', p_at AT TIME ZONE 'UTC') + interval '1 month') AT TIME ZONE 'UTC';
        WHEN 'DAILY' THEN
            window_start := date_trunc('day', p_at AT TIME ZONE 'UTC') AT TIME ZONE 'UTC';
            window_end := (date_trunc('day', p_at AT TIME ZONE 'UTC') + interval '1 day') AT TIME ZONE 'UTC';
        ELSE
            RAISE EXCEPTION 'Unknown budget period %', p_period;
    END CASE;
END
$$;

GRANT CREATE ON SCHEMA public TO operator_system;

-- Reserves the maximum cost of one model call against every budget that applies to the bound store, atomically.
-- Platform and store budgets are required (no budget = no paid call); organization and automation budgets apply
-- when they exist. Balances are locked in a fixed order (level, period, scope) so parallel reservations cannot
-- deadlock or overspend. The same idempotency key returns the first reservation and charges nothing again.
CREATE FUNCTION ai_reserve(p_idempotency_key text, p_automation_type text, p_agent_run_id uuid, p_price_id uuid,
                           p_max_input_tokens integer, p_max_output_tokens integer, p_amount numeric,
                           p_currency text, p_expires_at timestamptz)
    RETURNS TABLE (reservation_id uuid, outcome text, reason text, state text, amount numeric)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_org uuid := NULLIF(current_setting('app.organization_id', true), '')::uuid;
    v_store uuid := NULLIF(current_setting('app.store_id', true), '')::uuid;
    v_now timestamptz := now();
    v_price public.ai_price%ROWTYPE;
    v_policy record;
    v_balance public.ai_budget_balance%ROWTYPE;
    v_window record;
    v_id uuid;
    v_balances uuid[] := '{}';
    v_platform boolean := false;
    v_store_budget boolean := false;
BEGIN
    IF v_org IS NULL OR v_store IS NULL THEN
        RAISE EXCEPTION 'AI_SCOPE_NOT_BOUND' USING ERRCODE = '42501';
    END IF;
    IF p_idempotency_key IS NULL OR length(p_idempotency_key) NOT BETWEEN 1 AND 200
       OR p_automation_type IS NULL OR p_automation_type !~ '^[a-z0-9-]{1,64}$'
       OR p_max_input_tokens IS NULL OR p_max_input_tokens <= 0
       OR p_max_output_tokens IS NULL OR p_max_output_tokens <= 0
       OR p_amount IS NULL OR p_amount <= 0 OR p_currency IS NULL OR p_expires_at IS NULL OR p_expires_at <= v_now THEN
        RAISE EXCEPTION 'AI_RESERVATION_INVALID' USING ERRCODE = '22023';
    END IF;

    -- A repeated command returns the first reservation, whatever its state now.
    SELECT r.id, r.state, r.reserved_amount INTO v_id, state, amount
    FROM public.ai_budget_reservation r
    WHERE r.organization_id = v_org AND r.store_id = v_store AND r.idempotency_key = p_idempotency_key;
    IF FOUND THEN
        RETURN QUERY SELECT v_id, 'DUPLICATE'::text, NULL::text, state, amount;
        RETURN;
    END IF;

    -- Only a verified price that is in effect now, the newest one for its model and region.
    SELECT * INTO v_price FROM public.ai_price p WHERE p.id = p_price_id;
    IF NOT FOUND OR v_price.verified_at IS NULL OR v_price.effective_from > v_now
       OR v_price.currency <> p_currency
       OR EXISTS (SELECT 1 FROM public.ai_price n
                  WHERE n.provider = v_price.provider AND n.model = v_price.model AND n.region = v_price.region
                    AND n.effective_from > v_price.effective_from AND n.effective_from <= v_now) THEN
        RETURN QUERY SELECT NULL::uuid, 'DENIED'::text, 'PRICE_NOT_CURRENT'::text, NULL::text, NULL::numeric;
        RETURN;
    END IF;

    BEGIN
        FOR v_policy IN
            SELECT p.*
            FROM public.ai_budget_policy p
            WHERE p.superseded_at IS NULL
              AND ((p.level = 'PLATFORM')
                OR (p.level = 'ORGANIZATION' AND p.organization_id = v_org)
                OR (p.level = 'STORE' AND p.organization_id = v_org AND p.store_id = v_store)
                OR (p.level = 'AUTOMATION' AND p.organization_id = v_org AND p.store_id = v_store
                    AND p.automation_type = p_automation_type))
            ORDER BY CASE p.level WHEN 'PLATFORM' THEN 1 WHEN 'ORGANIZATION' THEN 2 WHEN 'STORE' THEN 3 ELSE 4 END,
                     p.period_type, p.scope_key
        LOOP
            IF v_policy.currency <> p_currency THEN
                RETURN QUERY SELECT NULL::uuid, 'DENIED'::text, ('CURRENCY_MISMATCH:' || v_policy.level)::text,
                    NULL::text, NULL::numeric;
                RETURN;
            END IF;
            v_platform := v_platform OR v_policy.level = 'PLATFORM';
            v_store_budget := v_store_budget OR v_policy.level = 'STORE';
            SELECT * INTO v_window FROM public.ai_budget_window(v_policy.period_type, v_now);
            INSERT INTO public.ai_budget_balance (id, level, scope_key, organization_id, store_id, period_type,
                                                  period_start, period_end, currency)
            VALUES (gen_random_uuid(), v_policy.level, v_policy.scope_key, v_policy.organization_id,
                    v_policy.store_id, v_policy.period_type, v_window.window_start, v_window.window_end,
                    v_policy.currency)
            ON CONFLICT (scope_key, period_type, period_start) DO NOTHING;
            SELECT * INTO v_balance FROM public.ai_budget_balance b
            WHERE b.scope_key = v_policy.scope_key AND b.period_type = v_policy.period_type
              AND b.period_start = v_window.window_start
            FOR UPDATE;
            IF v_balance.spent_amount + v_balance.reserved_amount + p_amount > v_policy.amount_limit THEN
                RETURN QUERY SELECT NULL::uuid, 'DENIED'::text,
                    ('BUDGET_EXHAUSTED:' || v_policy.level || ':' || v_policy.period_type)::text,
                    NULL::text, NULL::numeric;
                RETURN;
            END IF;
            v_balances := v_balances || v_balance.id;
        END LOOP;

        IF NOT v_platform THEN
            RETURN QUERY SELECT NULL::uuid, 'DENIED'::text, 'NO_PLATFORM_BUDGET'::text, NULL::text, NULL::numeric;
            RETURN;
        END IF;
        IF NOT v_store_budget THEN
            RETURN QUERY SELECT NULL::uuid, 'DENIED'::text, 'NO_STORE_BUDGET'::text, NULL::text, NULL::numeric;
            RETURN;
        END IF;

        v_id := gen_random_uuid();
        INSERT INTO public.ai_budget_reservation (id, organization_id, store_id, automation_type, agent_run_id,
                                                  idempotency_key, price_id, max_input_tokens, max_output_tokens,
                                                  estimated_max_cost, reserved_amount, currency, state, created_at,
                                                  expires_at, updated_at)
        VALUES (v_id, v_org, v_store, p_automation_type, p_agent_run_id, p_idempotency_key, p_price_id,
                p_max_input_tokens, p_max_output_tokens, p_amount, p_amount, p_currency, 'RESERVED', v_now,
                p_expires_at, v_now);
        UPDATE public.ai_budget_balance b
        SET reserved_amount = b.reserved_amount + p_amount, version = b.version + 1, updated_at = v_now
        WHERE b.id = ANY (v_balances);
        INSERT INTO public.ai_budget_charge (reservation_id, balance_id, organization_id, store_id, amount)
        SELECT v_id, unnest(v_balances), v_org, v_store, p_amount;
    EXCEPTION WHEN unique_violation THEN
        -- A parallel command with the same key won: return its reservation, nothing of ours is kept.
        SELECT r.id, r.state, r.reserved_amount INTO v_id, state, amount
        FROM public.ai_budget_reservation r
        WHERE r.organization_id = v_org AND r.store_id = v_store AND r.idempotency_key = p_idempotency_key;
        IF NOT FOUND THEN
            RAISE;
        END IF;
        RETURN QUERY SELECT v_id, 'DUPLICATE'::text, NULL::text, state, amount;
        RETURN;
    END;
    RETURN QUERY SELECT v_id, 'RESERVED'::text, NULL::text, 'RESERVED'::text, p_amount;
END
$$;
ALTER FUNCTION ai_reserve(text, text, uuid, uuid, integer, integer, numeric, text, timestamptz) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_reserve(text, text, uuid, uuid, integer, integer, numeric, text, timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_reserve(text, text, uuid, uuid, integer, integer, numeric, text, timestamptz)
    TO operator_runtime;

-- Releases a reservation whose call is known not to have been sent. Only RESERVED can be released; a call that may
-- have reached the provider (IN_PROGRESS, UNKNOWN) never is. Releasing twice changes nothing the second time.
CREATE FUNCTION ai_release(p_reservation_id uuid, p_reason text)
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
    IF v_reservation.state = 'RELEASED' THEN
        RETURN 'RELEASED';
    END IF;
    IF v_reservation.state <> 'RESERVED' THEN
        RAISE EXCEPTION 'AI_RESERVATION_NOT_RELEASABLE: %', v_reservation.state USING ERRCODE = '55000';
    END IF;
    -- Balances in the same fixed order as ai_reserve.
    PERFORM 1 FROM public.ai_budget_balance b
    JOIN public.ai_budget_charge c ON c.balance_id = b.id
    WHERE c.reservation_id = p_reservation_id
    ORDER BY CASE b.level WHEN 'PLATFORM' THEN 1 WHEN 'ORGANIZATION' THEN 2 WHEN 'STORE' THEN 3 ELSE 4 END,
             b.period_type, b.scope_key
    FOR UPDATE OF b;
    UPDATE public.ai_budget_balance b
    SET reserved_amount = b.reserved_amount - c.amount, version = b.version + 1, updated_at = now()
    FROM public.ai_budget_charge c
    WHERE c.reservation_id = p_reservation_id AND c.balance_id = b.id;
    UPDATE public.ai_budget_reservation
    SET state = 'RELEASED', state_reason = p_reason, updated_at = now()
    WHERE id = p_reservation_id;
    RETURN 'RELEASED';
END
$$;
ALTER FUNCTION ai_release(uuid, text) OWNER TO operator_system;
REVOKE ALL ON FUNCTION ai_release(uuid, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_release(uuid, text) TO operator_runtime;

REVOKE CREATE ON SCHEMA public FROM operator_system;

-- The prices the agent used so far (operator.agent.price-list anthropic-2026-11). They are not verified, so no paid
-- call can be reserved with them until someone checks them against the provider and sets verified_at.
INSERT INTO ai_price (id, provider, model, region, price_version, currency, input_per_mtok, output_per_mtok,
                      cache_write_per_mtok, cache_read_per_mtok, effective_from, source)
VALUES ('6f1c2a7e-0b51-4b61-9e43-2a8b7f1d0c01', 'anthropic', 'claude-opus-5-5', 'global', 'anthropic-2026-11', 'USD',
        4.00, 20.00, NULL, NULL, '2026-11-01T00:00:00Z',
        'Copied from operator.agent.price-list anthropic-2026-11; check against the provider before use'),
       ('6f1c2a7e-0b51-4b61-9e43-2a8b7f1d0c02', 'anthropic', 'claude-sonnet-5-5', 'global', 'anthropic-2026-11', 'USD',
        2.00, 10.00, NULL, NULL, '2026-11-01T00:00:00Z',
        'Copied from operator.agent.price-list anthropic-2026-11; check against the provider before use');
