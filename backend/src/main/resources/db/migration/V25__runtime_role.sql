-- ADR-0022 (revised): the API and the worker connect as operator_runtime, which owns nothing and has no BYPASSRLS.
-- Every store table forces row-level security, so not even the owner (operator_migrator) is exempt, and an unset
-- scope matches no row. The roles are created out of band (infra/postgres/init/02-operator-roles.sql).

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operator_runtime')
       OR NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'operator_system') THEN
        RAISE EXCEPTION 'ADR-0022: create the roles operator_runtime and operator_system before migrating';
    END IF;
    IF (SELECT rolsuper OR rolbypassrls FROM pg_roles WHERE rolname = 'operator_runtime') THEN
        RAISE EXCEPTION 'ADR-0022: operator_runtime must be NOSUPERUSER NOBYPASSRLS';
    END IF;
END
$$;

-- The runtime role: data access only. No DDL, no TRUNCATE, read-only schema history, append-only audit.
GRANT USAGE ON SCHEMA public TO operator_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO operator_runtime;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO operator_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO operator_runtime;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO operator_runtime;
DO $$
BEGIN
    IF to_regclass('public.flyway_schema_history') IS NOT NULL THEN
        REVOKE ALL ON flyway_schema_history FROM operator_runtime;
        GRANT SELECT ON flyway_schema_history TO operator_runtime;
    END IF;
END
$$;
REVOKE UPDATE, DELETE, TRUNCATE ON audit_event FROM operator_runtime;

-- Functions are not executable by everyone by default; each definer function grants EXECUTE explicitly.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;

-- The switched store role of V23 is retired: it keeps no privileges.
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON TABLES FROM operator_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public REVOKE ALL ON SEQUENCES FROM operator_app;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM operator_app;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM operator_app;
REVOKE USAGE ON SCHEMA public FROM operator_app;

-- Memberships are identity data: StoreAccess reads them to build a scope, before any store can be bound. They are
-- protected by the identity module's queries and the grants, not by RLS (ADR-0022 point 2).
DROP POLICY IF EXISTS store_isolation ON store_membership;
ALTER TABLE store_membership NO FORCE ROW LEVEL SECURITY;
ALTER TABLE store_membership DISABLE ROW LEVEL SECURITY;

-- Store tables: forced RLS and one fail-closed policy for the runtime role.
DO $$
DECLARE
    t text;
BEGIN
    FOR t IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind = 'r' AND c.relname NOT IN ('audit_event', 'store_membership')
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'store_id' AND NOT a.attisdropped)
          AND EXISTS (SELECT 1 FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attname = 'organization_id' AND NOT a.attisdropped)
    LOOP
        EXECUTE format('DROP POLICY IF EXISTS store_isolation ON %I', t);
        EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
        EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
        EXECUTE format($policy$
            CREATE POLICY store_isolation ON %I TO operator_runtime
                USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                       AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
                WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                            AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
            $policy$, t);
    END LOOP;
END
$$;

-- The audit: read the bound store only. Write entries of the bound store, organization-level entries of the bound
-- organization, or platform entries without organization and store (registration, refused access).
DROP POLICY IF EXISTS store_isolation ON audit_event;
DROP POLICY IF EXISTS store_isolation_insert ON audit_event;
ALTER TABLE audit_event FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON audit_event FOR SELECT TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);
CREATE POLICY store_isolation_insert ON audit_event FOR INSERT TO operator_runtime
    WITH CHECK ((store_id IS NULL AND organization_id IS NULL)
                OR (organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid
                    AND (store_id IS NULL
                         OR store_id = NULLIF(current_setting('app.store_id', true), '')::uuid)));

-- Cross-store questions (ADR-0022 point 4): the reviewed SECURITY DEFINER functions. They run as operator_system,
-- which nobody logs in as, read only what each function needs through a read policy for that role, and return
-- identifiers or counts, never store data. RowLevelSecurityTest fails on a definer function that is not listed here.
GRANT USAGE ON SCHEMA public TO operator_system;
-- A new owner of a function needs CREATE on its schema while the owner changes; revoked again at the end.
GRANT CREATE ON SCHEMA public TO operator_system;

-- Which store a WooCommerce webhook endpoint belongs to: the store is not known before the delivery is resolved.
GRANT SELECT (id, organization_id, store_id, provider, webhook_endpoint_key) ON integration_connection TO operator_system;
CREATE POLICY system_webhook_endpoint ON integration_connection FOR SELECT TO operator_system USING (true);

CREATE FUNCTION webhook_endpoint(p_key text)
    RETURNS TABLE (connection_id uuid, organization_id uuid, store_id uuid, provider text)
    LANGUAGE sql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
    SELECT c.id, c.organization_id, c.store_id, c.provider
    FROM public.integration_connection c
    WHERE c.webhook_endpoint_key = p_key
$$;
ALTER FUNCTION webhook_endpoint(text) OWNER TO operator_system;
REVOKE ALL ON FUNCTION webhook_endpoint(text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION webhook_endpoint(text) TO operator_runtime;

-- Operational gauges (ADR-0024): one count over all stores per name, for the worker's metrics.
GRANT SELECT ON action_execution, store_mutation_stop, outcome_observation, webhook_inbox, outbox_event,
    meta_connection, notification_delivery TO operator_system;
GRANT SELECT (status) ON integration_connection TO operator_system;
CREATE POLICY system_metrics ON action_execution FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON store_mutation_stop FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON outcome_observation FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON webhook_inbox FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON outbox_event FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON meta_connection FOR SELECT TO operator_system USING (true);
CREATE POLICY system_metrics ON notification_delivery FOR SELECT TO operator_system USING (true);

CREATE FUNCTION operational_metric(p_name text, p_max_attempts integer DEFAULT NULL)
    RETURNS double precision
    LANGUAGE plpgsql STABLE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v double precision;
BEGIN
    CASE p_name
        WHEN 'operator.executions.unknown' THEN
            SELECT count(*) INTO v FROM public.action_execution WHERE status = 'UNKNOWN';
        WHEN 'operator.executions.stuck' THEN
            SELECT count(*) INTO v FROM public.action_execution
            WHERE status IN ('IN_PROGRESS', 'APPLIED') AND updated_at < now() - interval '15 minutes';
        WHEN 'operator.mutation.stops.active' THEN
            SELECT count(*) INTO v FROM public.store_mutation_stop WHERE stopped;
        WHEN 'operator.observations.overdue' THEN
            SELECT count(*) INTO v FROM public.outcome_observation
            WHERE status = 'PENDING' AND due_at < now() - interval '1 hour';
        WHEN 'operator.webhook.inbox.pending' THEN
            SELECT count(*) INTO v FROM public.webhook_inbox WHERE status = 'RECEIVED';
        WHEN 'operator.webhook.inbox.oldest.age' THEN
            SELECT extract(epoch FROM now() - min(received_at)) INTO v FROM public.webhook_inbox
            WHERE status = 'RECEIVED';
        WHEN 'operator.outbox.pending' THEN
            SELECT count(*) INTO v FROM public.outbox_event WHERE dispatched_at IS NULL;
        WHEN 'operator.outbox.oldest.age' THEN
            SELECT extract(epoch FROM now() - min(created_at)) INTO v FROM public.outbox_event
            WHERE dispatched_at IS NULL;
        WHEN 'operator.connections.failing' THEN
            SELECT (SELECT count(*) FROM public.integration_connection WHERE status = 'ERROR')
                 + (SELECT count(*) FROM public.meta_connection WHERE status = 'ERROR') INTO v;
        WHEN 'operator.notification.deliveries.exhausted' THEN
            SELECT count(*) INTO v FROM public.notification_delivery
            WHERE status = 'FAILED' AND attempts >= p_max_attempts;
        ELSE
            RAISE EXCEPTION 'Unknown operational metric %', p_name;
    END CASE;
    RETURN coalesce(v, 0);
END
$$;
ALTER FUNCTION operational_metric(text, integer) OWNER TO operator_system;
REVOKE ALL ON FUNCTION operational_metric(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION operational_metric(text, integer) TO operator_runtime;

-- Audit retention (ADR-0022 point 6): the only way to delete audit entries. The runtime role has no DELETE on the
-- audit; this function deletes entries of the bound store (or, unbound, entries without a store) older than both
-- the cutoff and 365 days, inside the retention flag the append-only trigger requires.
GRANT SELECT, DELETE ON audit_event TO operator_system;
CREATE POLICY system_retention ON audit_event FOR SELECT TO operator_system USING (true);
CREATE POLICY system_retention_delete ON audit_event FOR DELETE TO operator_system USING (true);

CREATE FUNCTION apply_audit_retention(p_organization_id uuid, p_store_id uuid, p_cutoff timestamptz,
                                      p_batch integer)
    RETURNS integer
    LANGUAGE plpgsql VOLATILE SECURITY DEFINER SET search_path = pg_catalog, public AS
$$
DECLARE
    v_cutoff timestamptz := least(p_cutoff, now() - interval '365 days');
    v_deleted integer;
BEGIN
    IF p_store_id IS NOT NULL THEN
        IF p_store_id::text IS DISTINCT FROM NULLIF(current_setting('app.store_id', true), '')
           OR p_organization_id::text IS DISTINCT FROM NULLIF(current_setting('app.organization_id', true), '') THEN
            RAISE EXCEPTION 'apply_audit_retention: the store must be the bound store';
        END IF;
    ELSIF NULLIF(current_setting('app.store_id', true), '') IS NOT NULL THEN
        RAISE EXCEPTION 'apply_audit_retention: entries without a store are handled unbound';
    END IF;
    PERFORM set_config('operator.audit_retention', 'on', true);
    DELETE FROM public.audit_event
    WHERE id IN (SELECT e.id FROM public.audit_event e
                 WHERE e.occurred_at < v_cutoff
                   AND ((p_store_id IS NULL AND e.store_id IS NULL)
                        OR (e.store_id = p_store_id AND e.organization_id = p_organization_id))
                 LIMIT p_batch);
    GET DIAGNOSTICS v_deleted = ROW_COUNT;
    PERFORM set_config('operator.audit_retention', 'off', true);
    RETURN v_deleted;
END
$$;
ALTER FUNCTION apply_audit_retention(uuid, uuid, timestamptz, integer) OWNER TO operator_system;
REVOKE ALL ON FUNCTION apply_audit_retention(uuid, uuid, timestamptz, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION apply_audit_retention(uuid, uuid, timestamptz, integer) TO operator_runtime;

REVOKE CREATE ON SCHEMA public FROM operator_system;
