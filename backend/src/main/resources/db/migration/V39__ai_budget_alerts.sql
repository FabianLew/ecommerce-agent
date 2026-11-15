-- ADR-0027 section 14, budget alerts (E-54.7). A budget that reaches a threshold of its limit (by default 70, 90
-- and 100 percent, from the versioned parameter set operator.ai-budget-alerts) is recorded once per threshold and
-- budget period: the unique key makes a repeated or parallel evaluation record nothing twice, and a new period has
-- new balances, so its thresholds start again. Usage is reserved plus spent, the amount that blocks new calls, so an
-- alert comes no later than the refusal it warns about. The alerts are platform data: like balances and policies, a
-- store never sees them (stores never see platform economics); platform operators read them.
CREATE TABLE ai_budget_alert (
    id                uuid PRIMARY KEY,
    balance_id        uuid           NOT NULL REFERENCES ai_budget_balance (id),
    level             text           NOT NULL CHECK (level IN ('PLATFORM', 'ORGANIZATION', 'STORE', 'AUTOMATION')),
    scope_key         text           NOT NULL,
    organization_id   uuid,
    store_id          uuid,
    period_type       text           NOT NULL CHECK (period_type IN ('MONTHLY', 'DAILY')),
    period_start      timestamptz    NOT NULL,
    period_end        timestamptz    NOT NULL,
    threshold_percent integer        NOT NULL CHECK (threshold_percent BETWEEN 1 AND 100),
    amount_limit      numeric(20, 8) NOT NULL CHECK (amount_limit > 0),
    used_amount       numeric(20, 8) NOT NULL CHECK (used_amount >= 0),
    currency          char(3)        NOT NULL,
    policy_id         uuid           NOT NULL REFERENCES ai_budget_policy (id),
    parameter_version text           NOT NULL CHECK (length(parameter_version) BETWEEN 1 AND 60),
    created_at        timestamptz    NOT NULL DEFAULT now(),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    UNIQUE (balance_id, threshold_percent)
);
CREATE INDEX ai_budget_alert_created ON ai_budget_alert (created_at DESC);

CREATE FUNCTION ai_budget_alert_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ai_budget_alert rows are append-only (% not allowed)', TG_OP;
END
$$;
CREATE TRIGGER ai_budget_alert_append_only BEFORE UPDATE OR DELETE ON ai_budget_alert
    FOR EACH ROW EXECUTE FUNCTION ai_budget_alert_is_append_only();

ALTER TABLE ai_budget_alert ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_budget_alert FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON ai_budget_alert TO operator_runtime USING (false) WITH CHECK (false);
GRANT SELECT, INSERT ON ai_budget_alert TO operator_system;
CREATE POLICY governance ON ai_budget_alert TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY administration_read ON ai_budget_alert FOR SELECT TO operator_migrator USING (true);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- Records every threshold that a budget of the current periods has reached and that is not recorded yet, and
-- returns the new alerts. Thresholds are whole percents, from the backend's parameter set.
CREATE FUNCTION ai_record_budget_alerts(p_thresholds integer[], p_parameter_version text)
    RETURNS TABLE (id uuid, level text, scope_key text, organization_id uuid, store_id uuid, period_type text,
                   period_start timestamptz, period_end timestamptz, threshold_percent integer,
                   amount_limit numeric, used_amount numeric, currency char(3), created_at timestamptz)
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
#variable_conflict use_column
BEGIN
    IF p_thresholds IS NULL OR cardinality(p_thresholds) NOT BETWEEN 1 AND 10
       OR EXISTS (SELECT 1 FROM unnest(p_thresholds) t WHERE t IS NULL OR t NOT BETWEEN 1 AND 100)
       OR p_parameter_version IS NULL OR length(p_parameter_version) NOT BETWEEN 1 AND 60 THEN
        RAISE EXCEPTION 'AI_BUDGET_ALERT_INVALID' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    INSERT INTO public.ai_budget_alert AS a (id, balance_id, level, scope_key, organization_id, store_id,
                                             period_type, period_start, period_end, threshold_percent,
                                             amount_limit, used_amount, currency, policy_id, parameter_version)
    SELECT gen_random_uuid(), b.id, b.level, b.scope_key, b.organization_id, b.store_id, b.period_type,
           b.period_start, b.period_end, t.threshold, p.amount_limit, b.reserved_amount + b.spent_amount,
           b.currency, p.id, p_parameter_version
    FROM public.ai_budget_balance b
    JOIN public.ai_budget_policy p
      ON p.scope_key = b.scope_key AND p.period_type = b.period_type AND p.superseded_at IS NULL
     AND p.currency = b.currency AND p.amount_limit > 0
    CROSS JOIN LATERAL (SELECT DISTINCT unnest(p_thresholds) AS threshold) t
    WHERE b.period_start <= now() AND b.period_end > now()
      AND (b.reserved_amount + b.spent_amount) * 100 >= p.amount_limit * t.threshold
    ORDER BY b.scope_key, t.threshold
    ON CONFLICT (balance_id, threshold_percent) DO NOTHING
    RETURNING a.id, a.level, a.scope_key, a.organization_id, a.store_id, a.period_type, a.period_start,
              a.period_end, a.threshold_percent, a.amount_limit, a.used_amount, a.currency, a.created_at;
END
$$;

-- The recorded alerts, newest first, for platform operators.
CREATE FUNCTION ai_budget_alerts(p_since timestamptz, p_limit integer)
    RETURNS TABLE (id uuid, level text, scope_key text, organization_id uuid, store_id uuid, period_type text,
                   period_start timestamptz, period_end timestamptz, threshold_percent integer,
                   amount_limit numeric, used_amount numeric, currency char(3), created_at timestamptz)
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
#variable_conflict use_column
BEGIN
    IF p_limit IS NULL OR p_limit NOT BETWEEN 1 AND 1000 THEN
        RAISE EXCEPTION 'AI_BUDGET_ALERT_INVALID' USING ERRCODE = '22023';
    END IF;
    RETURN QUERY
    SELECT a.id, a.level, a.scope_key, a.organization_id, a.store_id, a.period_type, a.period_start, a.period_end,
           a.threshold_percent, a.amount_limit, a.used_amount, a.currency, a.created_at
    FROM public.ai_budget_alert a
    WHERE p_since IS NULL OR a.created_at >= p_since
    ORDER BY a.created_at DESC, a.threshold_percent DESC
    LIMIT p_limit;
END
$$;

REVOKE EXECUTE ON FUNCTION ai_record_budget_alerts(integer[], text) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_budget_alerts(timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_record_budget_alerts(integer[], text) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_budget_alerts(timestamptz, integer) TO operator_runtime;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
