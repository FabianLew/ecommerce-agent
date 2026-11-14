-- B-22: policy evaluations of proposals (ADR-0012). Recorded once; the pilot only evaluates.

CREATE TABLE policy_evaluation (
    id               uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    store_id         uuid        NOT NULL,
    proposal_id      uuid        NOT NULL,
    revision         integer     NOT NULL,
    policy_key       text        NOT NULL,
    policy_version   text        NOT NULL,
    -- Every parameter the evaluation used, so the outcome can be explained and replayed.
    parameters       jsonb       NOT NULL,
    mode             text        NOT NULL CHECK (mode IN ('EVALUATION_ONLY')),
    outcome          text        NOT NULL CHECK (outcome IN ('ALLOW', 'REQUIRE_APPROVAL', 'DENY', 'DEFER')),
    checks           jsonb       NOT NULL,
    evaluated_at     timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (proposal_id, store_id) REFERENCES decision_proposal (id, store_id)
);

CREATE INDEX policy_evaluation_proposal ON policy_evaluation (proposal_id, evaluated_at);

CREATE TRIGGER policy_evaluation_immutable BEFORE UPDATE OR DELETE ON policy_evaluation
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();
