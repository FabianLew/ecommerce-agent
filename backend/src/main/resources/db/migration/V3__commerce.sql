-- B-11: commerce model (ADR-0004 composite store keys, ADR-0005 explicit SQL).
-- Source data from integrations (WooCommerce first). No customer personal data: orders keep only what
-- metrics need (status, dates, currency, amounts, line quantities).
-- Child tables reference parents by (id, store_id), so a row can never point at another store's parent.

CREATE TABLE product (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL,
    store_id           uuid        NOT NULL,
    source             text        NOT NULL CHECK (source IN ('WOOCOMMERCE')),
    external_id        text        NOT NULL,
    name               text        NOT NULL,
    sku                text,
    product_type       text        NOT NULL,
    status             text        NOT NULL,
    source_updated_at  timestamptz,
    ingested_at        timestamptz NOT NULL,
    UNIQUE (store_id, source, external_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- A variant is the sellable unit: a Woo variation, or the product itself for simple products.
CREATE TABLE product_variant (
    id                 uuid PRIMARY KEY,
    organization_id    uuid        NOT NULL,
    store_id           uuid        NOT NULL,
    product_id         uuid        NOT NULL,
    source             text        NOT NULL CHECK (source IN ('WOOCOMMERCE')),
    external_id        text        NOT NULL,
    name               text        NOT NULL,
    sku                text,
    status             text        NOT NULL,
    -- Where stock is counted: on the variant, shared on the parent product, or not tracked at all.
    stock_tracking     text        NOT NULL CHECK (stock_tracking IN ('VARIANT', 'PARENT', 'NONE')),
    backorders         text        NOT NULL CHECK (backorders IN ('NO', 'NOTIFY', 'YES')),
    source_updated_at  timestamptz,
    ingested_at        timestamptz NOT NULL,
    UNIQUE (store_id, source, external_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (product_id, store_id) REFERENCES product (id, store_id)
);

CREATE INDEX product_variant_product_idx ON product_variant (product_id);

-- Append-only stock readings. sourceUpdatedAt protects against regressing state, observedAt is the
-- moment of a confirmed read, ingestedAt the write in our platform (document 03 §3).
CREATE TABLE inventory_observation (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    variant_id            uuid        NOT NULL,
    stock_quantity        integer,
    stock_status          text        NOT NULL CHECK (stock_status IN ('IN_STOCK', 'OUT_OF_STOCK', 'ON_BACKORDER')),
    -- How reservations relate to stock_quantity for this source (ADR on Woo stock semantics, B-13).
    reservation_semantics text        NOT NULL
        CHECK (reservation_semantics IN ('ALREADY_ACCOUNTED_FOR', 'SEPARATE_RESERVATIONS')),
    reserved_quantity     integer     CHECK (reserved_quantity IS NULL OR reserved_quantity >= 0),
    observation_source    text        NOT NULL CHECK (observation_source IN ('IMPORT', 'POLL', 'WEBHOOK')),
    source_updated_at     timestamptz,
    observed_at           timestamptz NOT NULL,
    ingested_at           timestamptz NOT NULL,
    CHECK (reservation_semantics = 'SEPARATE_RESERVATIONS' OR reserved_quantity IS NULL),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (variant_id, store_id) REFERENCES product_variant (id, store_id)
);

CREATE INDEX inventory_observation_variant_idx ON inventory_observation (variant_id, observed_at DESC);

CREATE TABLE customer_order (
    id                  uuid PRIMARY KEY,
    organization_id     uuid          NOT NULL,
    store_id            uuid          NOT NULL,
    source              text          NOT NULL CHECK (source IN ('WOOCOMMERCE')),
    external_id         text          NOT NULL,
    status              text          NOT NULL,
    currency            char(3)       NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    total_amount        numeric(19, 4) NOT NULL,
    tax_amount          numeric(19, 4) NOT NULL,
    discount_amount     numeric(19, 4) NOT NULL,
    shipping_amount     numeric(19, 4) NOT NULL,
    placed_at           timestamptz   NOT NULL,
    paid_at             timestamptz,
    completed_at        timestamptz,
    source_updated_at   timestamptz,
    ingested_at         timestamptz   NOT NULL,
    UNIQUE (store_id, source, external_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX customer_order_placed_idx ON customer_order (store_id, placed_at);

CREATE TABLE order_line (
    id                  uuid PRIMARY KEY,
    organization_id     uuid          NOT NULL,
    store_id            uuid          NOT NULL,
    order_id            uuid          NOT NULL,
    external_line_id    text          NOT NULL,
    -- Null when the sold product is unknown to our catalog (e.g. deleted in the source).
    variant_id          uuid,
    product_external_id text,
    sku                 text,
    quantity            integer       NOT NULL,
    line_total_amount   numeric(19, 4) NOT NULL,
    line_tax_amount     numeric(19, 4) NOT NULL,
    UNIQUE (order_id, external_line_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (order_id, store_id) REFERENCES customer_order (id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (variant_id, store_id) REFERENCES product_variant (id, store_id)
);

CREATE INDEX order_line_variant_idx ON order_line (variant_id);

-- Period for which orders of a source are known to be complete (watermark, document 03 §3).
CREATE TABLE order_sync_coverage (
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    source           text        NOT NULL CHECK (source IN ('WOOCOMMERCE')),
    complete_from    timestamptz NOT NULL,
    complete_until   timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    PRIMARY KEY (store_id, source),
    CHECK (complete_from <= complete_until),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
