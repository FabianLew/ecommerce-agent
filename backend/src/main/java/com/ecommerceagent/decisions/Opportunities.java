package com.ecommerceagent.decisions;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Episodes with at most one active per subject (IG-05): a concurrent second opening gets the same episode from
 * the unique index, never a second one.
 */
@Service
public class Opportunities {

    private final JdbcClient jdbc;
    private final Clock clock;

    Opportunities(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    public record Opened(Opportunity opportunity, boolean created) {
    }

    public Opened open(TrustedStoreScope scope, OpportunityCategory category, String subjectType, UUID subjectId) {
        Instant now = clock.instant();
        Optional<UUID> inserted = jdbc.sql("""
                        INSERT INTO opportunity (id, organization_id, store_id, category, subject_type, subject_id,
                                                 status, opened_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :category, :subjectType, :subjectId, 'OPEN', :now, :now)
                        ON CONFLICT (store_id, category, subject_type, subject_id)
                            WHERE status NOT IN ('RESOLVED', 'CLOSED') DO NOTHING
                        RETURNING id
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("category", category.name())
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .param("now", Timestamp.from(now))
                .query(UUID.class)
                .optional();
        if (inserted.isPresent()) {
            return new Opened(find(scope, inserted.get()).orElseThrow(), true);
        }
        return new Opened(active(scope, category, subjectType, subjectId).orElseThrow(), false);
    }

    public Optional<Opportunity> find(TrustedStoreScope scope, UUID opportunityId) {
        return jdbc.sql(SELECT + " AND id = :id")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("id", opportunityId)
                .query(Opportunities::map)
                .optional();
    }

    public Optional<Opportunity> active(TrustedStoreScope scope, OpportunityCategory category, String subjectType,
            UUID subjectId) {
        return jdbc.sql(SELECT + """
                         AND category = :category AND subject_type = :subjectType AND subject_id = :subjectId
                          AND status NOT IN ('RESOLVED', 'CLOSED')
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("category", category.name())
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .query(Opportunities::map)
                .optional();
    }

    /**
     * Moves an episode to {@code next} when the state machine allows it from its current status. The update is
     * a compare-and-set on the version, so two concurrent changes cannot both win.
     */
    public Opportunity transition(TrustedStoreScope scope, UUID opportunityId, OpportunityStatus next,
            String closeReason) {
        Opportunity current = find(scope, opportunityId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown opportunity " + opportunityId));
        if (current.status() == next) {
            return current;
        }
        if (!current.status().canMoveTo(next)) {
            throw new IllegalTransitionException(current.status() + " -> " + next);
        }
        Instant now = clock.instant();
        int updated = jdbc.sql("""
                        UPDATE opportunity
                        SET status = :next, version = version + 1, updated_at = :now,
                            closed_at = :closedAt, close_reason = :reason
                        WHERE id = :id AND version = :version
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("next", next.name())
                .param("now", Timestamp.from(now))
                .param("closedAt", next.isActive() ? null : Timestamp.from(now))
                .param("reason", next.isActive() ? null : closeReason)
                .param("id", opportunityId)
                .param("version", current.version())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
        if (updated == 0) {
            throw new IllegalTransitionException("Opportunity " + opportunityId + " changed concurrently");
        }
        return find(scope, opportunityId).orElseThrow();
    }

    private static final String SELECT = """
            SELECT id, category, subject_type, subject_id, status, version, close_reason, opened_at, updated_at,
                   closed_at
            FROM opportunity
            WHERE organization_id = :organizationId AND store_id = :storeId""";

    private static Opportunity map(ResultSet rs, int row) throws SQLException {
        Timestamp closed = rs.getTimestamp("closed_at");
        return new Opportunity(rs.getObject("id", UUID.class), OpportunityCategory.valueOf(rs.getString("category")),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class),
                OpportunityStatus.valueOf(rs.getString("status")), rs.getInt("version"), rs.getString("close_reason"),
                rs.getTimestamp("opened_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                closed == null ? null : closed.toInstant());
    }
}
