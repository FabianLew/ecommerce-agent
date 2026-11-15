-- ADR-0022 point 7: a legal hold stops every retention task for the store until it is released.
CREATE TABLE store_retention_hold (
    store_id         uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    reason           text        NOT NULL CHECK (length(reason) BETWEEN 1 AND 500),
    set_by           uuid        NOT NULL REFERENCES app_user (id),
    set_at           timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
ALTER TABLE store_retention_hold ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_retention_hold FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON store_retention_hold TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
