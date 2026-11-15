-- B-42: the technical outcome of an execution and its observation plan (ADR-0021). On the pilot branch: renumber
-- on merge.

-- One outcome per execution, written once it is final. The technical result is not a business result.
CREATE TABLE action_outcome (
    id                    uuid PRIMARY KEY,
    organization_id       uuid        NOT NULL,
    store_id              uuid        NOT NULL,
    execution_id          uuid        NOT NULL UNIQUE REFERENCES action_execution (id),
    authorized_action_id  uuid        NOT NULL REFERENCES authorized_action (id),
    proposal_id           uuid        NOT NULL,
    revision              integer     NOT NULL,
    approval_id           uuid        NOT NULL REFERENCES decision_approval (id),
    policy_evaluation_id  uuid        NOT NULL REFERENCES policy_evaluation (id),
    variant_id            uuid,
    ad_id                 uuid        NOT NULL,
    technical_result      text        NOT NULL CHECK (technical_result IN ('VERIFIED', 'NOOP', 'UNKNOWN', 'FAILED',
                                                                           'CONFLICT')),
    result_code           text,
    authorship            text        NOT NULL CHECK (authorship IN ('OPERATOR', 'UNCERTAIN', 'NONE')),
    pre_state             jsonb,
    post_state            jsonb,
    verification_source   text        NOT NULL,
    verified_at           timestamptz,
    recorded_at           timestamptz NOT NULL,
    UNIQUE (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id),
    FOREIGN KEY (proposal_id, store_id) REFERENCES decision_proposal (id, store_id)
);

CREATE TRIGGER action_outcome_immutable BEFORE UPDATE OR DELETE ON action_outcome
    FOR EACH ROW EXECUTE FUNCTION reject_mutation();

-- The observation plan of a verified outcome: checkpoints that record what the read models said, never "success".
CREATE TABLE outcome_observation (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    store_id          uuid        NOT NULL,
    outcome_id        uuid        NOT NULL,
    checkpoint        text        NOT NULL CHECK (checkpoint IN ('H24', 'D7')),
    due_at            timestamptz NOT NULL,
    status            text        NOT NULL CHECK (status IN ('PENDING', 'OBSERVED', 'INCONCLUSIVE')),
    observed_values   jsonb,
    limitations       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    observed_at       timestamptz,
    UNIQUE (outcome_id, checkpoint),
    FOREIGN KEY (outcome_id, store_id) REFERENCES action_outcome (id, store_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

CREATE INDEX outcome_observation_due ON outcome_observation (due_at) WHERE status = 'PENDING';
