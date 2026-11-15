-- ADR-0027 section 6.2, provider rate limiting (E-54.3). Every paid call takes a slot of its provider and model
-- before it is sent: at most a number of requests and of input and output tokens per minute, and at most a number
-- of calls in flight at once. A provider that answered with rate limiting is paused until its retry-after passes.
-- The limits come from the backend's versioned parameter set (operator.ai-provider-limits), never from a model; the
-- check and the insert are serialised by one advisory lock per provider and model, so they hold across instances.
-- The tables are platform wide and hold no store data; only the governance functions read and write them.
CREATE TABLE ai_provider_call (
    call_id        uuid PRIMARY KEY,
    provider       text        NOT NULL CHECK (provider ~ '^[a-z0-9-]{1,64}$'),
    model          text        NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    input_tokens   integer     NOT NULL CHECK (input_tokens >= 0),
    output_tokens  integer     NOT NULL CHECK (output_tokens >= 0),
    started_at     timestamptz NOT NULL,
    expires_at     timestamptz NOT NULL,
    finished_at    timestamptz,
    CHECK (expires_at > started_at),
    CHECK (finished_at IS NULL OR finished_at >= started_at)
);
CREATE INDEX ai_provider_call_window ON ai_provider_call (provider, model, started_at);

CREATE TABLE ai_provider_cooldown (
    provider     text        NOT NULL CHECK (provider ~ '^[a-z0-9-]{1,64}$'),
    model        text        NOT NULL CHECK (length(model) BETWEEN 1 AND 200),
    paused_until timestamptz NOT NULL,
    reason       text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 200),
    set_at       timestamptz NOT NULL,
    PRIMARY KEY (provider, model)
);

ALTER TABLE ai_provider_call ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_provider_call FORCE ROW LEVEL SECURITY;
ALTER TABLE ai_provider_cooldown ENABLE ROW LEVEL SECURITY;
ALTER TABLE ai_provider_cooldown FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON ai_provider_call TO operator_system;
GRANT SELECT, INSERT, UPDATE ON ai_provider_cooldown TO operator_system;
CREATE POLICY governance ON ai_provider_call TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY governance ON ai_provider_cooldown TO operator_system USING (true) WITH CHECK (true);
CREATE POLICY administration_read ON ai_provider_call FOR SELECT TO operator_migrator USING (true);
CREATE POLICY administration_read ON ai_provider_cooldown FOR SELECT TO operator_migrator USING (true);

GRANT CREATE ON SCHEMA public TO operator_system;
SET ROLE operator_system;

-- Takes a slot for one call. Answers GRANTED (also for a repeated call with the same id), PROVIDER_PAUSED,
-- REQUEST_LIMIT, INPUT_TOKEN_LIMIT, OUTPUT_TOKEN_LIMIT or PARALLEL_LIMIT. Token counts are the call's reserved
-- maxima, so the limits hold for the worst case.
CREATE FUNCTION ai_acquire_provider_slot(p_call_id uuid, p_provider text, p_model text, p_input_tokens integer,
                                         p_output_tokens integer, p_requests_per_minute integer,
                                         p_input_tokens_per_minute integer, p_output_tokens_per_minute integer,
                                         p_max_parallel integer, p_ttl interval)
    RETURNS text
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_now timestamptz := now();
    v_window timestamptz := now() - interval '1 minute';
    v_requests integer;
    v_input bigint;
    v_output bigint;
    v_parallel integer;
BEGIN
    IF p_call_id IS NULL OR p_provider IS NULL OR p_provider !~ '^[a-z0-9-]{1,64}$' OR p_model IS NULL
       OR length(p_model) NOT BETWEEN 1 AND 200 OR p_input_tokens IS NULL OR p_input_tokens < 0
       OR p_output_tokens IS NULL OR p_output_tokens < 0 OR p_requests_per_minute IS NULL
       OR p_requests_per_minute < 1 OR p_input_tokens_per_minute IS NULL OR p_input_tokens_per_minute < 1
       OR p_output_tokens_per_minute IS NULL OR p_output_tokens_per_minute < 1 OR p_max_parallel IS NULL
       OR p_max_parallel < 1 OR p_ttl IS NULL OR p_ttl <= interval '0' OR p_ttl > interval '1 hour' THEN
        RAISE EXCEPTION 'AI_PROVIDER_SLOT_INVALID' USING ERRCODE = '22023';
    END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('ai_provider_slot:' || p_provider || '/' || p_model, 0));
    IF EXISTS (SELECT 1 FROM public.ai_provider_call c WHERE c.call_id = p_call_id) THEN
        RETURN 'GRANTED';
    END IF;
    IF EXISTS (SELECT 1 FROM public.ai_provider_cooldown d
               WHERE d.provider = p_provider AND d.model = p_model AND d.paused_until > v_now) THEN
        RETURN 'PROVIDER_PAUSED';
    END IF;
    -- Calls older than the window and no longer in flight do not count any more.
    DELETE FROM public.ai_provider_call c
    WHERE c.provider = p_provider AND c.model = p_model AND c.started_at < v_now - interval '10 minutes'
      AND (c.finished_at IS NOT NULL OR c.expires_at <= v_now);
    SELECT count(*) FILTER (WHERE c.started_at > v_window),
           coalesce(sum(c.input_tokens) FILTER (WHERE c.started_at > v_window), 0),
           coalesce(sum(c.output_tokens) FILTER (WHERE c.started_at > v_window), 0),
           count(*) FILTER (WHERE c.finished_at IS NULL AND c.expires_at > v_now)
    INTO v_requests, v_input, v_output, v_parallel
    FROM public.ai_provider_call c
    WHERE c.provider = p_provider AND c.model = p_model;
    IF v_requests >= p_requests_per_minute THEN
        RETURN 'REQUEST_LIMIT';
    END IF;
    IF v_input + p_input_tokens > p_input_tokens_per_minute THEN
        RETURN 'INPUT_TOKEN_LIMIT';
    END IF;
    IF v_output + p_output_tokens > p_output_tokens_per_minute THEN
        RETURN 'OUTPUT_TOKEN_LIMIT';
    END IF;
    IF v_parallel >= p_max_parallel THEN
        RETURN 'PARALLEL_LIMIT';
    END IF;
    INSERT INTO public.ai_provider_call (call_id, provider, model, input_tokens, output_tokens, started_at,
                                         expires_at)
    VALUES (p_call_id, p_provider, p_model, p_input_tokens, p_output_tokens, v_now, v_now + p_ttl);
    RETURN 'GRANTED';
END
$$;

-- Ends a call's slot; it keeps counting in the per-minute window. Ending twice changes nothing.
CREATE FUNCTION ai_release_provider_slot(p_call_id uuid)
    RETURNS boolean
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
BEGIN
    UPDATE public.ai_provider_call c SET finished_at = greatest(now(), c.started_at)
    WHERE c.call_id = p_call_id AND c.finished_at IS NULL;
    RETURN FOUND;
END
$$;

-- Pauses a provider and model after it answered with rate limiting. A pause is only ever extended, never
-- shortened, and is at most one hour.
CREATE FUNCTION ai_pause_provider(p_provider text, p_model text, p_pause interval, p_reason text)
    RETURNS timestamptz
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_until timestamptz;
BEGIN
    IF p_provider IS NULL OR p_provider !~ '^[a-z0-9-]{1,64}$' OR p_model IS NULL
       OR length(p_model) NOT BETWEEN 1 AND 200 OR p_pause IS NULL OR p_pause <= interval '0'
       OR p_pause > interval '1 hour' OR p_reason IS NULL OR length(p_reason) NOT BETWEEN 1 AND 200 THEN
        RAISE EXCEPTION 'AI_PROVIDER_PAUSE_INVALID' USING ERRCODE = '22023';
    END IF;
    INSERT INTO public.ai_provider_cooldown AS d (provider, model, paused_until, reason, set_at)
    VALUES (p_provider, p_model, now() + p_pause, p_reason, now())
    ON CONFLICT (provider, model) DO UPDATE
        SET paused_until = greatest(d.paused_until, excluded.paused_until), reason = excluded.reason,
            set_at = excluded.set_at
    RETURNING d.paused_until INTO v_until;
    RETURN v_until;
END
$$;

REVOKE EXECUTE ON FUNCTION ai_acquire_provider_slot(uuid, text, text, integer, integer, integer, integer, integer,
    integer, interval) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_release_provider_slot(uuid) FROM PUBLIC;
REVOKE EXECUTE ON FUNCTION ai_pause_provider(text, text, interval, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION ai_acquire_provider_slot(uuid, text, text, integer, integer, integer, integer, integer,
    integer, interval) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_release_provider_slot(uuid) TO operator_runtime;
GRANT EXECUTE ON FUNCTION ai_pause_provider(text, text, interval, text) TO operator_runtime;
RESET ROLE;
REVOKE CREATE ON SCHEMA public FROM operator_system;
