package com.ecommerceagent.automation.notifications;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.SystemStoreRunner;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Tells the people of a store what happened (ADR-0021): the notification is stored once per key, then delivered by
 * e-mail to everyone who may approve decisions. A delivery failure never touches the action it reports (IG-23).
 */
@Service
@NamedInterface("notifications")
public class OperatorNotifier {

    private final MeterRegistry meters;

    static final String SWEEP = "notification-sweep";
    private static final Logger log = LoggerFactory.getLogger(OperatorNotifier.class);
    private static final String COLUMNS = """
            n.id, n.kind, n.severity, n.code, n.parameters, n.opportunity_id, n.proposal_id, n.execution_id,
            n.created_at""";

    private final StoreAccess storeAccess;
    private final SystemStoreRunner stores;
    private final MailProvider mail;
    private final NotificationSettings settings;
    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    OperatorNotifier(StoreAccess storeAccess, SystemStoreRunner stores, MailProvider mail, NotificationSettings settings, JdbcClient jdbc,
            TransactionTemplate transactions, Clock clock, MeterRegistry meters) {
        this.meters = meters;
        this.storeAccess = storeAccess;
        this.stores = stores;
        this.mail = mail;
        this.settings = settings;
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Stores the notification (once per key) with its deliveries, then sends what is still open.
     *
     * @throws NotificationDeliveryException when some e-mails could not be sent now; they are retried later
     */
    public UUID notify(TrustedStoreScope scope, NotificationEvent event) {
        UUID id = transactions.execute(status -> store(scope, event));
        int failed = deliver(scope, id);
        if (failed > 0) {
            throw new NotificationDeliveryException(failed);
        }
        return id;
    }

    /** Newest first, before the keyset position when given; {@code read} is this person's mark. */
    public List<Notification> list(TrustedStoreScope scope, UUID userId, Instant beforeAt, UUID beforeId,
            int limit) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        , r.user_id IS NOT NULL AS read
                        FROM operator_notification n
                        LEFT JOIN operator_notification_read r ON r.notification_id = n.id AND r.user_id = :userId
                        WHERE n.organization_id = :organizationId AND n.store_id = :storeId
                          AND (CAST(:beforeAt AS timestamptz) IS NULL OR (n.created_at, n.id) < (:beforeAt, :beforeId))
                        ORDER BY n.created_at DESC, n.id DESC
                        LIMIT :limit
                        """)
                .param("userId", userId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("beforeAt", beforeAt == null ? null : Timestamp.from(beforeAt),
                        java.sql.Types.TIMESTAMP)
                .param("beforeId", beforeId, java.sql.Types.OTHER)
                .param("limit", limit)
                .query((rs, n) -> map(rs, rs.getBoolean("read")))
                .list();
    }

    public long unreadCount(TrustedStoreScope scope, UUID userId) {
        return jdbc.sql("""
                        SELECT count(*) FROM operator_notification n
                        WHERE n.organization_id = :organizationId AND n.store_id = :storeId
                          AND NOT EXISTS (SELECT 1 FROM operator_notification_read r
                                          WHERE r.notification_id = n.id AND r.user_id = :userId)
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("userId", userId)
                .query(Long.class)
                .single();
    }

    /** Marks the notification read for this person; false when it is not a notification of the store. */
    public boolean markRead(TrustedStoreScope scope, UUID userId, UUID notificationId) {
        return jdbc.sql("""
                        INSERT INTO operator_notification_read (notification_id, organization_id, store_id, user_id,
                                                                read_at)
                        SELECT n.id, n.organization_id, n.store_id, :userId, :now FROM operator_notification n
                        WHERE n.id = :id AND n.organization_id = :organizationId AND n.store_id = :storeId
                        ON CONFLICT (notification_id, user_id) DO NOTHING
                        """)
                .param("userId", userId)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", notificationId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update() > 0 || exists(scope, notificationId);
    }

    public Optional<Notification> byKey(TrustedStoreScope scope, String dedupKey) {
        return jdbc.sql("SELECT " + COLUMNS + """

                        FROM operator_notification n
                        WHERE n.organization_id = :organizationId AND n.store_id = :storeId AND n.dedup_key = :key
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("key", dedupKey)
                .query((rs, n) -> map(rs, false))
                .optional();
    }

    /**
     * Sends the open deliveries of the notification. A delivery is claimed before sending, so a concurrent sweep
     * cannot send it at the same time; one left in SENDING by a crash is taken again after its claim expires.
     */
    private int deliver(TrustedStoreScope scope, UUID notificationId) {
        Notification notification = jdbc.sql("SELECT " + COLUMNS + """

                        FROM operator_notification n
                        WHERE n.id = :id AND n.organization_id = :organizationId AND n.store_id = :storeId
                        """)
                .param("id", notificationId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> map(rs, false))
                .single();
        Map<UUID, RegisteredUser> recipients = new TreeMap<>();
        storeAccess.membersWith(scope, StorePermission.DECISION_APPROVE).forEach(user -> recipients.put(user.id(), user));
        int failed = 0;
        for (Claimed claimed : claim(scope, notificationId)) {
            RegisteredUser user = recipients.get(claimed.recipient());
            if (user == null || user.email() == null || user.email().isBlank()) {
                // The person lost the permission or has no address: nothing to send, and nothing to retry.
                finishDelivery(claimed.id(), "FAILED", "RECIPIENT_UNAVAILABLE", settings.maxAttempts());
                continue;
            }
            try {
                mail.send(new MailProvider.MailMessage(claimed.id().toString(), user.email(),
                        NotificationText.subject(notification), NotificationText.body(notification,
                                settings.appBaseUrl() + "/stores/" + scope.storeId())));
                finishDelivery(claimed.id(), "SENT", null, 0);
            } catch (RuntimeException e) {
                failed++;
                log.warn("E-mail delivery {} failed: {}", claimed.id(), e.getClass().getSimpleName());
                finishDelivery(claimed.id(), "FAILED", "PROVIDER_ERROR", 0);
            }
        }
        return failed;
    }

    /**
     * Retries the open deliveries whose last attempt is older than the retry interval; returns how many still
     * failed. Runs as a system actor of each store: it only sends what was stored, it changes nothing else.
     */
    public int retryOpen(int limit) {
        AtomicInteger failed = new AtomicInteger();
        stores.forEachStore(SWEEP, scope -> failed.addAndGet(retryOpen(scope, limit)));
        return failed.get();
    }

    private int retryOpen(TrustedStoreScope scope, int limit) {
        List<UUID> open = jdbc.sql("""
                        SELECT DISTINCT notification_id FROM notification_delivery
                        WHERE organization_id = :organizationId AND store_id = :storeId
                          AND (status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND claimed_until < :now))
                          AND attempts < :max AND updated_at <= :due
                        LIMIT :limit
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("now", Timestamp.from(clock.instant()))
                .param("max", settings.maxAttempts())
                .param("due", Timestamp.from(clock.instant().minus(settings.retryInterval())))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        int failed = 0;
        for (UUID notificationId : open) {
            failed += deliver(scope, notificationId);
        }
        return failed;
    }

    private UUID store(TrustedStoreScope scope, NotificationEvent event) {
        Instant now = clock.instant();
        ObjectNode parameters = JsonNodeFactory.instance.objectNode();
        new TreeMap<>(event.parameters()).forEach(parameters::put);
        jdbc.sql("""
                        INSERT INTO operator_notification (id, organization_id, store_id, dedup_key, kind, severity, code,
                                                           parameters, opportunity_id, proposal_id, execution_id,
                                                           created_at)
                        VALUES (:id, :organizationId, :storeId, :key, :kind, :severity, :code, CAST(:parameters AS jsonb),
                                :opportunityId, :proposalId, :executionId, :now)
                        ON CONFLICT (store_id, dedup_key) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("key", event.dedupKey())
                .param("kind", event.kind().name())
                .param("severity", event.kind().severity().name())
                .param("code", event.code(), java.sql.Types.VARCHAR)
                .param("parameters", CanonicalJson.write(parameters))
                .param("opportunityId", event.opportunityId(), java.sql.Types.OTHER)
                .param("proposalId", event.proposalId(), java.sql.Types.OTHER)
                .param("executionId", event.executionId(), java.sql.Types.OTHER)
                .param("now", Timestamp.from(now))
                .update();
        UUID id = byKey(scope, event.dedupKey()).orElseThrow().id();
        for (RegisteredUser user : storeAccess.membersWith(scope, StorePermission.DECISION_APPROVE)) {
            jdbc.sql("""
                            INSERT INTO notification_delivery (id, organization_id, store_id, notification_id, channel,
                                                               recipient_user_id, status, created_at, updated_at)
                            VALUES (:id, :organizationId, :storeId, :notificationId, 'EMAIL', :userId, 'PENDING', :now,
                                    :now)
                            ON CONFLICT (notification_id, channel, recipient_user_id) DO NOTHING
                            """)
                    .param("id", UUID.randomUUID())
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .param("notificationId", id)
                    .param("userId", user.id())
                    .param("now", Timestamp.from(now))
                    .update();
        }
        return id;
    }

    private record Claimed(UUID id, UUID recipient) {
    }

    private List<Claimed> claim(TrustedStoreScope scope, UUID notificationId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                        UPDATE notification_delivery
                        SET status = 'SENDING', attempts = attempts + 1, claimed_until = :until, updated_at = :now
                        WHERE notification_id = :notificationId AND organization_id = :organizationId
                          AND store_id = :storeId AND attempts < :max
                          AND (status IN ('PENDING', 'FAILED') OR (status = 'SENDING' AND claimed_until < :now))
                        RETURNING id, recipient_user_id
                        """)
                .param("until", Timestamp.from(now.plus(settings.claimDuration())))
                .param("now", Timestamp.from(now))
                .param("notificationId", notificationId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("max", settings.maxAttempts())
                .query((rs, n) -> new Claimed(rs.getObject("id", UUID.class),
                        rs.getObject("recipient_user_id", UUID.class)))
                .list();
    }

    private void finishDelivery(UUID deliveryId, String status, String errorCode, int attemptsFloor) {
        Instant now = clock.instant();
        jdbc.sql("""
                        UPDATE notification_delivery
                        SET status = :status, error_code = :error, claimed_until = NULL, updated_at = :now,
                            attempts = GREATEST(attempts, :floor),
                            sent_at = CASE WHEN :status = 'SENT' THEN :now ELSE sent_at END
                        WHERE id = :id
                        """)
                .param("status", status)
                .param("error", errorCode, java.sql.Types.VARCHAR)
                .param("now", Timestamp.from(now))
                .param("floor", attemptsFloor)
                .param("id", deliveryId)
                .update();
        meters.counter("operator.notification.deliveries", "status", status,
                "code", errorCode == null ? "none" : errorCode).increment();
    }

    private boolean exists(TrustedStoreScope scope, UUID notificationId) {
        return jdbc.sql("""
                        SELECT count(*) FROM operator_notification
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", notificationId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Long.class)
                .single() > 0;
    }

    private static Notification map(java.sql.ResultSet rs, boolean read) throws java.sql.SQLException {
        JsonNode parameters = CanonicalJson.read(rs.getString("parameters"));
        Map<String, String> values = new TreeMap<>();
        parameters.properties().forEach(entry -> values.put(entry.getKey(), entry.getValue().asString()));
        return new Notification(rs.getObject("id", UUID.class), NotificationKind.valueOf(rs.getString("kind")),
                NotificationKind.Severity.valueOf(rs.getString("severity")), rs.getString("code"), Map.copyOf(values),
                rs.getObject("opportunity_id", UUID.class), rs.getObject("proposal_id", UUID.class),
                rs.getObject("execution_id", UUID.class), rs.getTimestamp("created_at").toInstant(), read);
    }
}
