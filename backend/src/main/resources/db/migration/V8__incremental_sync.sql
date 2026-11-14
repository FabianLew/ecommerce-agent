-- B-16: polling and reconciliation (ADR-0010). V7 is reserved for the audit fixes (sync_run dispatch).

-- Where the next incremental read starts, per connection and stream. Moves only after a complete read.
CREATE TABLE sync_watermark (
    connection_id    uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    stream           text        NOT NULL CHECK (stream IN ('PRODUCTS', 'ORDERS')),
    watermark        timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (connection_id, stream),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (connection_id, store_id) REFERENCES integration_connection (id, store_id) ON DELETE CASCADE
);

-- One row per poll or reconciliation run; Integration Health reads the latest ones.
CREATE TABLE sync_check (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    connection_id    uuid        NOT NULL,
    kind             text        NOT NULL CHECK (kind IN ('POLL', 'RECONCILE')),
    status           text        NOT NULL CHECK (status IN ('SUCCEEDED', 'INCOMPLETE', 'FAILED')),
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz NOT NULL,
    changes          integer     NOT NULL DEFAULT 0,
    -- Changes found by this run that a configured webhook should have delivered (ADR-0010).
    missed_webhooks  integer     NOT NULL DEFAULT 0,
    error_code       text,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (connection_id, store_id) REFERENCES integration_connection (id, store_id) ON DELETE CASCADE
);

CREATE INDEX sync_check_latest_idx ON sync_check (connection_id, kind, started_at DESC);
