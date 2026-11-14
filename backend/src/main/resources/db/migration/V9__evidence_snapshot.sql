-- B-18: evidence and context snapshots (ADR-0011). Both are written once and never changed.

CREATE FUNCTION reject_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION '% is immutable', TG_TABLE_NAME USING ERRCODE = 'restrict_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TABLE evidence (
    id                  uuid PRIMARY KEY,
    organization_id     uuid        NOT NULL,
    store_id            uuid        NOT NULL,
    version             integer     NOT NULL DEFAULT 1 CHECK (version = 1),
    kind                text        NOT NULL CHECK (kind IN ('OBSERVATION', 'DERIVED_METRIC')),
    evidence_type       text        NOT NULL,
    resource_ref        text        NOT NULL,
    definition_version  text,
    -- Canonical record (ADR-0011): the hash is computed from exactly this content.
    content             jsonb       NOT NULL,
    content_hash        text        NOT NULL CHECK (content_hash LIKE 'sha256:%'),
    input_evidence_ids  uuid[]      NOT NULL DEFAULT '{}',
    observed_at         timestamptz NOT NULL,
    available_at        timestamptz NOT NULL,
    computed_at         timestamptz NOT NULL,
    created_at          timestamptz NOT NULL,
    UNIQUE (store_id, content_hash),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX evidence_resource_idx ON evidence (store_id, resource_ref, observed_at DESC);

CREATE TRIGGER evidence_immutable BEFORE UPDATE OR DELETE ON evidence
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE context_snapshot (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    -- The episode; its foreign key arrives with the opportunity table (B-19).
    opportunity_id   uuid,
    content_schema   text        NOT NULL,
    as_of            timestamptz NOT NULL,
    content          jsonb       NOT NULL,
    content_hash     text        NOT NULL CHECK (content_hash LIKE 'sha256:%'),
    created_at       timestamptz NOT NULL,
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX context_snapshot_opportunity_idx ON context_snapshot (store_id, opportunity_id, as_of DESC);

CREATE TRIGGER context_snapshot_immutable BEFORE UPDATE OR DELETE ON context_snapshot
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

CREATE TABLE context_snapshot_evidence (
    snapshot_id      uuid NOT NULL,
    evidence_id      uuid NOT NULL,
    organization_id  uuid NOT NULL,
    store_id         uuid NOT NULL,
    PRIMARY KEY (snapshot_id, evidence_id),
    -- Both sides must belong to the same store.
    FOREIGN KEY (snapshot_id, store_id) REFERENCES context_snapshot (id, store_id),
    FOREIGN KEY (evidence_id, store_id) REFERENCES evidence (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE TRIGGER context_snapshot_evidence_immutable BEFORE UPDATE OR DELETE ON context_snapshot_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
