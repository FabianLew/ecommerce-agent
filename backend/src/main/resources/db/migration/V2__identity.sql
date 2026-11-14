-- B-06: tenancy model (ADR-0004). Every store-scoped business table carries organization_id and store_id
-- with a composite FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id).
-- The rule is enforced by IdentitySchemaTest; audit_event is the documented exception.

CREATE TABLE organization (
    id          uuid PRIMARY KEY,
    name        text        NOT NULL,
    created_at  timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE store (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL REFERENCES organization (id),
    name             text        NOT NULL,
    currency         char(3)     NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    timezone         text        NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (id, organization_id)
);

CREATE TABLE app_user (
    id            uuid PRIMARY KEY,
    issuer        text        NOT NULL,
    subject       text        NOT NULL,
    email         text,
    display_name  text,
    created_at    timestamptz NOT NULL DEFAULT now(),
    UNIQUE (issuer, subject)
);

CREATE TABLE store_membership (
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    user_id          uuid        NOT NULL REFERENCES app_user (id),
    role             text        NOT NULL
        CHECK (role IN ('OWNER', 'DECISION_OPERATOR', 'SUPPORT_OPERATOR', 'ANALYST')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (store_id, user_id, role),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX store_membership_user_idx ON store_membership (user_id);

-- Append-only audit trail. Tenant columns are nullable for events without a resolved scope, so it has no
-- composite FK. The trigger guards against application mistakes, not against a privileged database
-- administrator; retention and PII minimization are required by ADR-0004.
CREATE TABLE audit_event (
    id               uuid PRIMARY KEY,
    occurred_at      timestamptz NOT NULL,
    organization_id  uuid,
    store_id         uuid,
    actor_user_id    uuid,
    actor_service    text,
    action           text        NOT NULL,
    resource_type    text,
    resource_id      text,
    details          jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CHECK (actor_user_id IS NULL OR actor_service IS NULL)
);

CREATE INDEX audit_event_store_idx ON audit_event (store_id, occurred_at);

CREATE FUNCTION audit_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'audit_event is append-only (% not allowed)', TG_OP;
END;
$$;

CREATE TRIGGER audit_event_append_only
    BEFORE UPDATE OR DELETE ON audit_event
    FOR EACH ROW EXECUTE FUNCTION audit_event_is_append_only();
