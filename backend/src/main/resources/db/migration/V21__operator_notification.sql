-- B-41: operator notifications, per-person read marks and e-mail deliveries (ADR-0021). On the pilot branch:
-- renumber on merge.

-- One row per deduplication key: structured, never rendered text; the UI renders it in the viewer's language.
CREATE TABLE operator_notification (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    store_id          uuid        NOT NULL,
    dedup_key         text        NOT NULL,
    kind              text        NOT NULL,
    severity          text        NOT NULL CHECK (severity IN ('INFO', 'WARNING', 'CRITICAL')),
    code              text,
    parameters        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    opportunity_id    uuid,
    proposal_id       uuid,
    execution_id      uuid,
    created_at        timestamptz NOT NULL,
    UNIQUE (store_id, dedup_key),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX operator_notification_recent ON operator_notification (store_id, created_at DESC, id DESC);

CREATE TABLE operator_notification_read (
    notification_id   uuid        NOT NULL,
    organization_id   uuid        NOT NULL,
    store_id          uuid        NOT NULL,
    user_id           uuid        NOT NULL REFERENCES app_user (id),
    read_at           timestamptz NOT NULL,
    PRIMARY KEY (notification_id, user_id),
    FOREIGN KEY (notification_id, store_id) REFERENCES operator_notification (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- One delivery per notification, channel and recipient: a retry never sends twice to the same person.
CREATE TABLE notification_delivery (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    store_id          uuid        NOT NULL,
    notification_id   uuid        NOT NULL,
    channel           text        NOT NULL CHECK (channel IN ('EMAIL')),
    recipient_user_id uuid        NOT NULL REFERENCES app_user (id),
    status            text        NOT NULL CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED')),
    attempts          integer     NOT NULL DEFAULT 0,
    error_code        text,
    claimed_until     timestamptz,
    sent_at           timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    UNIQUE (notification_id, channel, recipient_user_id),
    FOREIGN KEY (notification_id, store_id) REFERENCES operator_notification (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX notification_delivery_open ON notification_delivery (status, updated_at)
    WHERE status IN ('PENDING', 'SENDING', 'FAILED');
