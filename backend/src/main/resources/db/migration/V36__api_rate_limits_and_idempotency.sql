-- ADR-0027 section 6.1 and section 12, API abuse protection (E-54.5). Requests that start background work or a
-- check, and webhook deliveries, are counted in one-minute windows per user, per store, per endpoint and per webhook
-- endpoint key; over the versioned limit (operator.api-rate-limits) the API answers 429 with Retry-After. The count
-- is one atomic upsert, so it holds across instances. A command sent with an Idempotency-Key is answered once: a
-- repeat with the same key gets the stored answer and starts nothing. Both tables are platform wide; they hold
-- identifiers and stored responses of the platform's own API, no store data, and are cleaned by the application.
CREATE TABLE api_rate_window (
    bucket       text        NOT NULL CHECK (length(bucket) BETWEEN 1 AND 300),
    window_start timestamptz NOT NULL,
    hits         integer     NOT NULL CHECK (hits >= 1),
    PRIMARY KEY (bucket, window_start)
);
CREATE INDEX api_rate_window_start ON api_rate_window (window_start);

CREATE TABLE api_idempotency (
    owner           text        NOT NULL CHECK (length(owner) BETWEEN 1 AND 300),
    idempotency_key text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request         text        NOT NULL CHECK (length(request) BETWEEN 1 AND 1000),
    status          integer CHECK (status IS NULL OR status BETWEEN 100 AND 599),
    content_type    text CHECK (content_type IS NULL OR length(content_type) <= 200),
    body            text CHECK (body IS NULL OR length(body) <= 65536),
    created_at      timestamptz NOT NULL,
    completed_at    timestamptz,
    PRIMARY KEY (owner, idempotency_key)
);
CREATE INDEX api_idempotency_created ON api_idempotency (created_at);

ALTER TABLE api_rate_window ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_rate_window FORCE ROW LEVEL SECURITY;
ALTER TABLE api_idempotency ENABLE ROW LEVEL SECURITY;
ALTER TABLE api_idempotency FORCE ROW LEVEL SECURITY;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_rate_window TO operator_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON api_idempotency TO operator_runtime;
CREATE POLICY api_protection ON api_rate_window TO operator_runtime USING (true) WITH CHECK (true);
CREATE POLICY api_protection ON api_idempotency TO operator_runtime USING (true) WITH CHECK (true);
