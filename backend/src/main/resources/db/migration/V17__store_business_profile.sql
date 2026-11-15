-- B-33: Store Business Profile versions and onboarding progress (ADR-0018). On the pilot branch: renumber on merge.

CREATE TABLE store_business_profile (
    id                     uuid PRIMARY KEY,
    organization_id        uuid        NOT NULL,
    store_id               uuid        NOT NULL,
    version                integer     NOT NULL CHECK (version >= 1),
    status                 text        NOT NULL CHECK (status IN ('DRAFT', 'APPROVED', 'SUPERSEDED')),
    goal                   text        CHECK (goal IN ('PROFITABILITY', 'GROWTH', 'BALANCED')),
    planning_horizon_days  integer     CHECK (planning_horizon_days BETWEEN 7 AND 365),
    risk_preference        text        CHECK (risk_preference IN ('LOW', 'MEDIUM', 'HIGH')),
    tax_basis              text        NOT NULL DEFAULT 'EX_TAX' CHECK (tax_basis IN ('EX_TAX', 'INC_TAX')),
    priorities             text[]      NOT NULL DEFAULT '{}',
    planned_ad_spend_minor bigint      CHECK (planned_ad_spend_minor >= 0),
    planned_ad_spend_currency text,
    planned_ad_spend_period text       CHECK (planned_ad_spend_period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
    note                   text        CHECK (length(note) <= 2000),
    -- Optimistic version of the draft row; an approved row keeps the value it was approved with.
    revision               integer     NOT NULL DEFAULT 1,
    created_by             uuid,
    approved_by            uuid,
    approved_at            timestamptz,
    effective_from         timestamptz,
    created_at             timestamptz NOT NULL,
    updated_at             timestamptz NOT NULL,
    UNIQUE (store_id, version),
    CHECK (status = 'DRAFT' OR (approved_by IS NOT NULL AND approved_at IS NOT NULL AND goal IS NOT NULL
        AND planning_horizon_days IS NOT NULL AND risk_preference IS NOT NULL)),
    CHECK ((planned_ad_spend_minor IS NULL) = (planned_ad_spend_currency IS NULL)),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE UNIQUE INDEX store_business_profile_one_draft ON store_business_profile (store_id) WHERE status = 'DRAFT';
CREATE UNIQUE INDEX store_business_profile_one_approved ON store_business_profile (store_id)
    WHERE status = 'APPROVED';

CREATE TABLE onboarding_progress (
    store_id          uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    current_step      text        NOT NULL
        CHECK (current_step IN ('CONNECT_STORE', 'CONNECT_TOOLS', 'BUSINESS_DATA', 'START_MONITORING')),
    acknowledgements  text[]      NOT NULL DEFAULT '{}',
    version           integer     NOT NULL DEFAULT 1,
    updated_by        uuid,
    updated_at        timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
