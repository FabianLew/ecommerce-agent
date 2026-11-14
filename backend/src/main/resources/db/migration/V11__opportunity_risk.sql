-- B-19: risk observations and episodes (ADR-0011).

CREATE TABLE opportunity (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    category         text        NOT NULL CHECK (category IN ('INVENTORY_RISK')),
    subject_type     text        NOT NULL CHECK (subject_type IN ('PRODUCT_VARIANT')),
    subject_id       uuid        NOT NULL,
    status           text        NOT NULL CHECK (status IN ('OPEN', 'ANALYZING', 'AWAITING_DECISION', 'MONITORING',
                                                            'WAITING_DATA', 'ACTING', 'RESOLVED', 'CLOSED')),
    version          integer     NOT NULL DEFAULT 1,
    close_reason     text,
    opened_at        timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    closed_at        timestamptz,
    CHECK ((status IN ('RESOLVED', 'CLOSED')) = (closed_at IS NOT NULL)),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- At most one active episode per subject and category (IG-05); a closed one leaves room for the next.
CREATE UNIQUE INDEX opportunity_one_active ON opportunity (store_id, category, subject_type, subject_id)
    WHERE status NOT IN ('RESOLVED', 'CLOSED');

ALTER TABLE context_snapshot
    ADD FOREIGN KEY (opportunity_id, store_id) REFERENCES opportunity (id, store_id);

CREATE TABLE inventory_risk_observation (
    id                  uuid PRIMARY KEY,
    organization_id     uuid          NOT NULL,
    store_id            uuid          NOT NULL,
    variant_id          uuid          NOT NULL,
    observed_at         timestamptz   NOT NULL,
    -- The newest confirmation of the stock state the assessment used.
    stock_confirmed_at  timestamptz,
    available_to_sell   integer,
    days_of_cover       numeric(12, 4),
    outcome             text          NOT NULL CHECK (outcome IN ('AT_RISK', 'NOT_AT_RISK', 'UNKNOWN')),
    reason              text,
    threshold_days      numeric(8, 2) NOT NULL,
    detector_version    text          NOT NULL,
    opportunity_id      uuid,
    FOREIGN KEY (variant_id, store_id) REFERENCES product_variant (id, store_id),
    FOREIGN KEY (opportunity_id, store_id) REFERENCES opportunity (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX inventory_risk_observation_variant_idx ON inventory_risk_observation (store_id, variant_id, observed_at DESC);
