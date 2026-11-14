-- B-20: ads and SKU-to-ad mappings (ADR-0011). Filled from a fixture until the Meta adapter (F2).

CREATE TABLE ad (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    platform              text        NOT NULL CHECK (platform IN ('META')),
    external_id           text        NOT NULL,
    account_external_id   text        NOT NULL,
    name                  text        NOT NULL,
    -- The ad's own status and the status that results from its campaign and ad set (IG-18).
    own_status            text        NOT NULL CHECK (own_status IN ('ACTIVE', 'PAUSED', 'ARCHIVED', 'DELETED')),
    effective_status      text        NOT NULL,
    creative_fingerprint  text        NOT NULL,
    observed_at           timestamptz NOT NULL,
    source                text        NOT NULL CHECK (source IN ('FIXTURE', 'META_API')),
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    UNIQUE (store_id, platform, external_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- One mapping per ad; every change increments the version, so a decision can name the version it relied on.
CREATE TABLE ad_mapping (
    id                              uuid PRIMARY KEY,
    organization_id                 uuid        NOT NULL,
    store_id                        uuid        NOT NULL,
    ad_id                           uuid        NOT NULL,
    scope                           text        NOT NULL CHECK (scope IN ('SINGLE_VARIANT', 'MULTI_PRODUCT')),
    version                         integer     NOT NULL CHECK (version > 0),
    verification_status             text        NOT NULL
        CHECK (verification_status IN ('VERIFIED', 'UNVERIFIED', 'REVOKED')),
    verified_at                     timestamptz,
    -- The creative the mapping was confirmed for; a different current creative invalidates it (IG-09).
    verified_creative_fingerprint   text,
    created_at                      timestamptz NOT NULL,
    updated_at                      timestamptz NOT NULL,
    CHECK ((verification_status = 'VERIFIED') = (verified_at IS NOT NULL AND verified_creative_fingerprint IS NOT NULL)),
    UNIQUE (store_id, ad_id),
    UNIQUE (id, store_id),
    FOREIGN KEY (ad_id, store_id) REFERENCES ad (id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE TABLE ad_mapping_variant (
    mapping_id       uuid NOT NULL,
    variant_id       uuid NOT NULL,
    organization_id  uuid NOT NULL,
    store_id         uuid NOT NULL,
    PRIMARY KEY (mapping_id, variant_id),
    FOREIGN KEY (mapping_id, store_id) REFERENCES ad_mapping (id, store_id) ON DELETE CASCADE,
    FOREIGN KEY (variant_id, store_id) REFERENCES product_variant (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX ad_mapping_variant_variant_idx ON ad_mapping_variant (store_id, variant_id);
