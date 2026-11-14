-- B-12: store integration connections and their encrypted credentials (ADR-0007).
-- Secrets live in a separate table, so queries for lists and DTOs never select them.

CREATE TABLE integration_connection (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL,
    store_id            uuid        NOT NULL,
    provider            text        NOT NULL CHECK (provider IN ('WOOCOMMERCE')),
    store_url           text        NOT NULL,
    status              text        NOT NULL
        CHECK (status IN ('PENDING_CREDENTIALS', 'CHECK_REQUIRED', 'CONNECTED', 'ERROR')),
    -- Last 4 characters of the API key, for display only.
    key_hint            text,
    last_check_at       timestamptz,
    last_success_at     timestamptz,
    last_error_code     text,
    last_check_warnings jsonb       NOT NULL DEFAULT '[]'::jsonb,
    source_currency     text,
    created_at          timestamptz NOT NULL,
    updated_at          timestamptz NOT NULL,
    UNIQUE (store_id, provider),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- AES-256-GCM: ciphertext includes the tag; AAD binds it to connection, store and credential name.
CREATE TABLE integration_credential (
    connection_id    uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    name             text        NOT NULL,
    ciphertext       bytea       NOT NULL,
    nonce            bytea       NOT NULL,
    key_id           text        NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (connection_id, name),
    FOREIGN KEY (connection_id, store_id) REFERENCES integration_connection (id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
