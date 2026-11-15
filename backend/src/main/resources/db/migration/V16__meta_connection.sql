-- B-31: Meta ad account connection and its encrypted token (ADR-0017). On the pilot branch: renumber on merge.

CREATE TABLE meta_connection (
    id                   uuid PRIMARY KEY,
    organization_id      uuid        NOT NULL,
    store_id             uuid        NOT NULL,
    -- Numeric ad account id, without the act_ prefix.
    account_id           text        NOT NULL CHECK (account_id ~ '^[0-9]{1,30}$'),
    status               text        NOT NULL
        CHECK (status IN ('PENDING_CREDENTIALS', 'CHECK_REQUIRED', 'CONNECTED', 'ERROR')),
    token_hint           text,
    account_name         text,
    currency             text,
    can_write            boolean     NOT NULL DEFAULT false,
    last_check_at        timestamptz,
    last_success_at      timestamptz,
    last_error_code      text,
    last_sync_at         timestamptz,
    last_sync_error      text,
    ads_seen             integer     NOT NULL DEFAULT 0,
    next_sync_after      timestamptz,
    created_at           timestamptz NOT NULL,
    updated_at           timestamptz NOT NULL,
    UNIQUE (store_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE TABLE meta_credential (
    connection_id    uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    name             text        NOT NULL,
    ciphertext       bytea       NOT NULL,
    nonce            bytea       NOT NULL,
    key_id           text        NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (connection_id, name),
    FOREIGN KEY (connection_id, store_id) REFERENCES meta_connection (id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
