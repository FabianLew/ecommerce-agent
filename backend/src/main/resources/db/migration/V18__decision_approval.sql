-- B-37: approvals and rejections of one proposal revision (ADR-0020). On the pilot branch: renumber on merge.

ALTER TABLE policy_evaluation DROP CONSTRAINT policy_evaluation_mode_check;
ALTER TABLE policy_evaluation ADD CONSTRAINT policy_evaluation_mode_check
    CHECK (mode IN ('EVALUATION_ONLY', 'EXECUTE_ON_APPROVAL'));

CREATE TABLE decision_approval (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    proposal_id           uuid        NOT NULL,
    revision              integer     NOT NULL,
    decision              text        NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    -- What exactly was approved: the proposal's canonical payload and the action (type, target, parameters).
    payload_hash          text        NOT NULL,
    action_hash           text,
    policy_evaluation_id  uuid,
    reason                text        CHECK (length(reason) <= 1000),
    decided_by            uuid        NOT NULL,
    decided_at            timestamptz NOT NULL,
    expires_at            timestamptz NOT NULL,
    idempotency_key       text        NOT NULL CHECK (length(idempotency_key) BETWEEN 1 AND 200),
    request_hash          text        NOT NULL,
    -- One decision per proposal; a repeated command with the same key returns it.
    UNIQUE (proposal_id),
    UNIQUE (store_id, idempotency_key),
    CHECK (decision = 'REJECTED' OR (action_hash IS NOT NULL AND policy_evaluation_id IS NOT NULL)),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (proposal_id, store_id) REFERENCES decision_proposal (id, store_id),
    FOREIGN KEY (decided_by) REFERENCES app_user (id)
);

CREATE TRIGGER decision_approval_immutable BEFORE UPDATE OR DELETE ON decision_approval
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
