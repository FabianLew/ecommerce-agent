-- B-38: AuthorizedAction, the execution ledger, target leases and limit slots (ADR-0020). On the pilot branch:
-- renumber on merge.

-- A short-lived authorization issued by the backend only. Several may share an idempotency key when an expired one is
-- re-issued for the same action; the ledger row of the key is shared.
CREATE TABLE authorized_action (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    proposal_id           uuid        NOT NULL,
    revision              integer     NOT NULL,
    payload_hash          text        NOT NULL,
    action_hash           text        NOT NULL,
    approval_id           uuid        NOT NULL REFERENCES decision_approval (id),
    policy_evaluation_id  uuid        NOT NULL REFERENCES policy_evaluation (id),
    action_type           text        NOT NULL CHECK (action_type IN ('PAUSE_AD')),
    connection_id         uuid        NOT NULL,
    target_type           text        NOT NULL CHECK (target_type IN ('META_AD')),
    target_id             uuid        NOT NULL,
    target_external_id    text        NOT NULL,
    parameters_hash       text        NOT NULL,
    expected_status       text        NOT NULL,
    mapping_version       integer     NOT NULL,
    creative_fingerprint  text,
    idempotency_key       text        NOT NULL,
    issued_at             timestamptz NOT NULL,
    expires_at            timestamptz NOT NULL,
    CHECK (expires_at > issued_at),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (proposal_id, store_id) REFERENCES decision_proposal (id, store_id)
);

CREATE INDEX authorized_action_key ON authorized_action (idempotency_key, issued_at);

CREATE TRIGGER authorized_action_immutable BEFORE UPDATE OR DELETE ON authorized_action
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- One row per idempotency key: the durable register of attempts and their effect.
CREATE TABLE action_execution (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    idempotency_key       text        NOT NULL UNIQUE,
    authorized_action_id  uuid        NOT NULL REFERENCES authorized_action (id),
    action_type           text        NOT NULL,
    target_type           text        NOT NULL,
    target_id             uuid        NOT NULL,
    status                text        NOT NULL CHECK (status IN ('PLANNED', 'IN_PROGRESS', 'APPLIED', 'VERIFIED', 'NOOP',
                                                                 'UNKNOWN', 'FAILED', 'CONFLICT')),
    pre_state             jsonb,
    request_hash          text,
    provider_request_id   text,
    result_code           text,
    -- Whether the write request may have reached the provider; a FAILED row without it releases its slot.
    write_sent            boolean     NOT NULL DEFAULT false,
    attempts              integer     NOT NULL DEFAULT 0,
    version               integer     NOT NULL DEFAULT 1,
    created_at            timestamptz NOT NULL,
    updated_at            timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX action_execution_target ON action_execution (store_id, target_type, target_id, updated_at);

-- Serialization of actions on one target across workers, without a transaction held during the API call.
CREATE TABLE action_target_lease (
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    target_type      text        NOT NULL,
    target_id        uuid        NOT NULL,
    owner            text        NOT NULL,
    lease_until      timestamptz NOT NULL,
    PRIMARY KEY (store_id, target_type, target_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- The rolling limit on executed actions; a retry of a key never takes a second slot.
CREATE TABLE action_limit_slot (
    execution_id     uuid PRIMARY KEY REFERENCES action_execution (id),
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    action_type      text        NOT NULL,
    reserved_at      timestamptz NOT NULL,
    released_at      timestamptz,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX action_limit_slot_window ON action_limit_slot (store_id, action_type, reserved_at);
