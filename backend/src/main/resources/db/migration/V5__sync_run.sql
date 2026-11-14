-- B-13: import runs of an integration connection (ADR-0008). One running import per store and provider.

CREATE TABLE sync_run (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    connection_id         uuid        NOT NULL,
    kind                  text        NOT NULL CHECK (kind IN ('INITIAL_IMPORT')),
    status                text        NOT NULL CHECK (status IN ('RUNNING', 'SUCCEEDED', 'INCOMPLETE', 'FAILED')),
    requested_by_user_id  uuid,
    window_from           timestamptz NOT NULL,
    window_until          timestamptz NOT NULL,
    products_imported     integer,
    variants_imported     integer,
    orders_imported       integer,
    orders_expected       integer,
    error_code            text,
    started_at            timestamptz NOT NULL,
    finished_at           timestamptz,
    CHECK (window_from < window_until),
    FOREIGN KEY (connection_id, store_id) REFERENCES integration_connection (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE UNIQUE INDEX sync_run_one_running_per_connection ON sync_run (connection_id) WHERE status = 'RUNNING';
CREATE INDEX sync_run_latest ON sync_run (store_id, connection_id, started_at DESC);
