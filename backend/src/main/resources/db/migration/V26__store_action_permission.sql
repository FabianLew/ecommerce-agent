-- ADR-0025 point 2: the level an action type may reach in a store. A missing row means PROPOSE_ONLY.
CREATE TABLE store_action_permission (
    store_id         uuid        NOT NULL,
    organization_id  uuid        NOT NULL,
    action_type      text        NOT NULL CHECK (action_type IN ('PAUSE_AD')),
    level            text        NOT NULL CHECK (level IN ('OFF', 'PROPOSE_ONLY', 'APPROVAL_REQUIRED')),
    reason           text        CHECK (reason IS NULL OR length(reason) <= 500),
    version          integer     NOT NULL CHECK (version > 0),
    changed_by       uuid        NOT NULL REFERENCES app_user (id),
    changed_at       timestamptz NOT NULL,
    PRIMARY KEY (store_id, action_type),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);

-- A store table: forced, fail-closed RLS for the runtime role (ADR-0022 point 2). The default privileges of V25
-- already grant the runtime role its DML.
ALTER TABLE store_action_permission ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_action_permission FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON store_action_permission TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
