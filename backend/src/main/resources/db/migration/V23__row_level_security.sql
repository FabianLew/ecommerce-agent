-- ADR-0022: row-level security as a second line of store isolation, and the audit retention exception.
-- The application switches to operator_app only while a verified store scope is bound (RowSecurityDataSource);
-- the owner role (migrations, jobs that walk all stores) is not forced through RLS.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operator_app') THEN
        CREATE ROLE operator_app NOLOGIN;
    END IF;
END
$$;

GRANT operator_app TO CURRENT_USER;
GRANT USAGE ON SCHEMA public TO operator_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO operator_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO operator_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operator_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO operator_app;
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON flyway_schema_history FROM operator_app;
    END IF;
END
$$;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM operator_app;

-- Every table that holds store data: both columns must equal the bound scope. An unset scope matches nothing.
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname <> 'audit_event'
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'store_id' AND NOT a.attisdropped)
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped)
    LOOP
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format($policy$
            CREATE POLICY store_isolation ON %I TO operator_app
                USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                       AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
                WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                            AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
            $policy$, t);
    END LOOP;
END
$$;

-- The audit: read only the bound store; write entries of the bound store or without a store.
ALTER TABLE audit_event ENABLE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON audit_event FOR SELECT TO operator_app
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
CREATE POLICY store_isolation_insert ON audit_event FOR INSERT TO operator_app
    WITH CHECK (store_id IS NULL
                OR (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                    AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid));

-- Retention (ADR-0022 point 5) is the only allowed deletion: inside a transaction that set
-- operator.audit_retention = 'on', and never for entries younger than 365 days.
CREATE OR REPLACE FUNCTION audit_event_is_append_only() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    IF TG_OP = 'DELETE'
       AND current_setting('operator.audit_retention', true) = 'on'
       AND OLD.occurred_at < now() - interval '365 days' THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION 'audit_event is append-only (% not allowed)', TG_OP;
END
$$;
