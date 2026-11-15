-- ADR-0026 point 6: every observation records the detector's parameters (older rows keep only the version and the
-- threshold).
ALTER TABLE inventory_risk_observation ADD COLUMN detector_parameters jsonb;

-- ADR-0026 point 5: the store's optional alert deputy, set from the version seen by a member with STORE_MANAGE.
CREATE TABLE store_alert_setting (
    store_id         uuid PRIMARY KEY,
    organization_id  uuid        NOT NULL,
    deputy_user_id   uuid        REFERENCES app_user (id),
    version          integer     NOT NULL CHECK (version > 0),
    changed_by       uuid        NOT NULL REFERENCES app_user (id),
    changed_at       timestamptz NOT NULL,
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
ALTER TABLE store_alert_setting ENABLE ROW LEVEL SECURITY;
ALTER TABLE store_alert_setting FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON store_alert_setting TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
