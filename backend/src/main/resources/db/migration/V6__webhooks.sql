-- B-14: WooCommerce webhooks (ADR-0009): endpoint key, inbox with deduplication, outbox for domain events.

-- Random key in the public delivery URL; not the connection or store id.
ALTER TABLE integration_connection
    ADD COLUMN webhook_endpoint_key   text,
    ADD COLUMN webhook_secret_set_at  timestamptz,
    ADD COLUMN last_webhook_at        timestamptz;
UPDATE integration_connection SET webhook_endpoint_key = replace(gen_random_uuid()::text, '-', '');
ALTER TABLE integration_connection
    ALTER COLUMN webhook_endpoint_key SET NOT NULL,
    ADD CONSTRAINT integration_connection_webhook_key_format CHECK (webhook_endpoint_key ~ '^[0-9a-f]{32}$'),
    ADD CONSTRAINT integration_connection_webhook_key_unique UNIQUE (webhook_endpoint_key);

-- Accepted deliveries. The payload is minimized before it is stored: no customer data (ADR-0008, ADR-0009).
CREATE TABLE webhook_inbox (
    id                  uuid PRIMARY KEY,
    -- Arrival order: items are processed in the order they were accepted.
    seq                 bigint      GENERATED ALWAYS AS IDENTITY,
    organization_id     uuid        NOT NULL,
    store_id            uuid        NOT NULL,
    connection_id       uuid        NOT NULL,
    topic               text        NOT NULL,
    source_webhook_id   text,
    source_delivery_id  text,
    -- SHA-256 of the raw body: the same body delivered again is the same row.
    payload_sha256      text        NOT NULL CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    payload             jsonb       NOT NULL,
    status              text        NOT NULL CHECK (status IN ('RECEIVED', 'PROCESSED', 'IGNORED', 'FAILED')),
    attempts            integer     NOT NULL DEFAULT 0,
    next_attempt_at     timestamptz NOT NULL,
    outcome             text,
    received_at         timestamptz NOT NULL,
    processed_at        timestamptz,
    UNIQUE (connection_id, topic, payload_sha256),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (connection_id, store_id) REFERENCES integration_connection (id, store_id) ON DELETE CASCADE
);

CREATE INDEX webhook_inbox_pending_idx ON webhook_inbox (next_attempt_at, seq) WHERE status = 'RECEIVED';

-- Domain events written in the same transaction as the state they describe; dispatched in B-15.
CREATE TABLE outbox_event (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    event_type       text        NOT NULL,
    aggregate_type   text        NOT NULL,
    aggregate_id     text        NOT NULL,
    payload          jsonb       NOT NULL,
    -- Re-processing the same input never creates a second event.
    dedup_key        text        NOT NULL,
    created_at       timestamptz NOT NULL,
    dispatched_at    timestamptz,
    attempts         integer     NOT NULL DEFAULT 0,
    UNIQUE (store_id, dedup_key),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX outbox_event_pending_idx ON outbox_event (created_at) WHERE dispatched_at IS NULL;
