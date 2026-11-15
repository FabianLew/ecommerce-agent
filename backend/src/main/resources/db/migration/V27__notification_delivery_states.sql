-- ADR-0021 point 2: e-mail delivery is not exactly-once. An uncertain dispatch is UNKNOWN, never PENDING; every
-- attempt keeps the same Message-ID; provider events move a delivery to DELIVERED or BOUNCED.
ALTER TABLE notification_delivery DROP CONSTRAINT notification_delivery_status_check;
ALTER TABLE notification_delivery ADD CONSTRAINT notification_delivery_status_check CHECK (status IN (
    'PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN', 'EXHAUSTED', 'DELIVERED', 'BOUNCED'));
ALTER TABLE notification_delivery
    ADD COLUMN message_id          text,
    ADD COLUMN provider_message_id text,
    ADD COLUMN delivered_at        timestamptz;

DROP INDEX notification_delivery_open;
CREATE INDEX notification_delivery_open ON notification_delivery (status, updated_at)
    WHERE status IN ('PENDING', 'SENDING', 'FAILED', 'UNKNOWN');

-- Deliveries that V21 left FAILED for good become EXHAUSTED. The migrator is subject to the forced policies
-- (ADR-0022, consequences), so this data change has its own policy for the duration of the migration.
CREATE POLICY migration_v27 ON notification_delivery TO CURRENT_USER USING (true) WITH CHECK (true);
UPDATE notification_delivery SET status = 'EXHAUSTED'
WHERE status = 'FAILED' AND (error_code = 'RECIPIENT_UNAVAILABLE' OR attempts >= 5);
-- A delivery left SENDING may have been taken by the provider: it is reconciled, not sent again.
UPDATE notification_delivery SET status = 'UNKNOWN', error_code = 'CLAIM_EXPIRED', claimed_until = NULL
WHERE status = 'SENDING';
DROP POLICY migration_v27 ON notification_delivery;

-- Provider events (delivered, bounced, complaint), once per provider event id.
CREATE TABLE notification_delivery_event (
    id                uuid PRIMARY KEY,
    organization_id   uuid        NOT NULL,
    store_id          uuid        NOT NULL,
    delivery_id       uuid        NOT NULL REFERENCES notification_delivery (id),
    kind              text        NOT NULL CHECK (kind IN ('DELIVERED', 'BOUNCED', 'COMPLAINT')),
    provider_event_id text        NOT NULL CHECK (length(provider_event_id) <= 200),
    occurred_at       timestamptz NOT NULL,
    received_at       timestamptz NOT NULL,
    UNIQUE (delivery_id, provider_event_id),
    FOREIGN KEY (store_id, organization_id) REFERENCES store (id, organization_id)
);
ALTER TABLE notification_delivery_event ENABLE ROW LEVEL SECURITY;
ALTER TABLE notification_delivery_event FORCE ROW LEVEL SECURITY;
CREATE POLICY store_isolation ON notification_delivery_event TO operator_runtime
    USING (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
           AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid)
    WITH CHECK (store_id = NULLIF(current_setting('app.store_id', true), '')::uuid
                AND organization_id = NULLIF(current_setting('app.organization_id', true), '')::uuid);

-- The exhausted-deliveries gauge of ADR-0024 now counts EXHAUSTED. The function is replaced as V25 created it: the
-- schema owner drops it, and operator_system needs CREATE on the schema only while it becomes the owner again.
DROP FUNCTION operational_metric(text, integer);
GRANT CREATE ON SCHEMA public TO operator_system;
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
            SELECT count(*) INTO v FROM public.notification_delivery WHERE status = 'EXHAUSTED';
        ELSE
            RAISE EXCEPTION 'Unknown operational metric %', p_name;
    END CASE;
    RETURN coalesce(v, 0);
END
$$;
ALTER FUNCTION operational_metric(text, integer) OWNER TO operator_system;
REVOKE ALL ON FUNCTION operational_metric(text, integer) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION operational_metric(text, integer) TO operator_runtime;
REVOKE CREATE ON SCHEMA public FROM operator_system;
