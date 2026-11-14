-- B-21: DecisionProposal v1 and rejected proposal attempts (ADR-0012).

CREATE TABLE decision_proposal (
    id                   uuid PRIMARY KEY,
    organization_id      uuid        NOT NULL,
    store_id             uuid        NOT NULL,
    opportunity_id       uuid        NOT NULL,
    revision             integer     NOT NULL CHECK (revision >= 1),
    schema_version       text        NOT NULL CHECK (schema_version = '1'),
    context_snapshot_id  uuid        NOT NULL,
    proposer_kind        text        NOT NULL CHECK (proposer_kind IN ('BASELINE', 'AGENT')),
    proposer_name        text        NOT NULL,
    proposer_version     text        NOT NULL,
    agent_run_id         uuid,
    disposition          text        NOT NULL CHECK (disposition IN ('RECOMMEND_ACTION', 'NO_ACTION', 'MONITOR',
                                                                     'INSUFFICIENT_DATA', 'ESCALATE')),
    -- Canonical record (ADR-0011 point 2); the hash is computed from exactly this content.
    content              jsonb       NOT NULL,
    payload_hash         text        NOT NULL CHECK (payload_hash LIKE 'sha256:%'),
    status               text        NOT NULL CHECK (status IN ('PROPOSED', 'APPROVED', 'REJECTED', 'EXPIRED',
                                                                'SUPERSEDED')),
    created_at           timestamptz NOT NULL,
    expires_at           timestamptz NOT NULL CHECK (expires_at > created_at),
    status_changed_at    timestamptz NOT NULL,
    UNIQUE (opportunity_id, revision),
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (opportunity_id, store_id) REFERENCES opportunity (id, store_id),
    FOREIGN KEY (context_snapshot_id, store_id) REFERENCES context_snapshot (id, store_id)
);

-- At most one proposal of an episode waits for a decision.
CREATE UNIQUE INDEX decision_proposal_one_proposed ON decision_proposal (opportunity_id) WHERE status = 'PROPOSED';

-- Content is immutable; only the status moves, and only away from PROPOSED.
CREATE FUNCTION decision_proposal_guard() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'decision_proposal is immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    IF (NEW.id, NEW.organization_id, NEW.store_id, NEW.opportunity_id, NEW.revision, NEW.schema_version,
        NEW.context_snapshot_id, NEW.proposer_kind, NEW.proposer_name, NEW.proposer_version, NEW.agent_run_id,
        NEW.disposition, NEW.content, NEW.payload_hash, NEW.created_at, NEW.expires_at)
       IS DISTINCT FROM
       (OLD.id, OLD.organization_id, OLD.store_id, OLD.opportunity_id, OLD.revision, OLD.schema_version,
        OLD.context_snapshot_id, OLD.proposer_kind, OLD.proposer_name, OLD.proposer_version, OLD.agent_run_id,
        OLD.disposition, OLD.content, OLD.payload_hash, OLD.created_at, OLD.expires_at) THEN
        RAISE EXCEPTION 'decision_proposal content is immutable' USING ERRCODE = 'restrict_violation';
    END IF;
    IF NEW.status IS DISTINCT FROM OLD.status AND OLD.status <> 'PROPOSED' THEN
        RAISE EXCEPTION 'decision_proposal status % is final', OLD.status USING ERRCODE = 'restrict_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER decision_proposal_guard BEFORE UPDATE OR DELETE ON decision_proposal
    FOR EACH ROW EXECUTE FUNCTION decision_proposal_guard();

-- An invalid proposal never becomes a proposal: the attempt is kept with its error codes (IG-10).
CREATE TABLE proposal_rejection (
    id                   uuid PRIMARY KEY,
    organization_id      uuid        NOT NULL,
    store_id             uuid        NOT NULL,
    -- Null when the attempt named an episode this store does not have; the payload keeps what it named.
    opportunity_id       uuid,
    context_snapshot_id  uuid,
    proposer_kind        text        NOT NULL CHECK (proposer_kind IN ('BASELINE', 'AGENT')),
    proposer_name        text        NOT NULL,
    proposer_version     text        NOT NULL,
    agent_run_id         uuid,
    payload              jsonb       NOT NULL,
    payload_hash         text        NOT NULL CHECK (payload_hash LIKE 'sha256:%'),
    error_codes          text[]      NOT NULL CHECK (cardinality(error_codes) > 0),
    errors               jsonb       NOT NULL,
    created_at           timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (opportunity_id, store_id) REFERENCES opportunity (id, store_id)
);

CREATE TRIGGER proposal_rejection_immutable BEFORE UPDATE OR DELETE ON proposal_rejection
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
