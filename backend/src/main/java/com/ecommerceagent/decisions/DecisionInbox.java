package com.ecommerceagent.decisions;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Read side of the Decision Inbox (ADR-0014): one row per episode with at least one valid proposal, showing its
 * latest revision. Older revisions stay reachable through {@link DecisionProposals#ofOpportunity}.
 */
@Service
public class DecisionInbox {

    /** One inbox row: the episode's latest revision and the episode status that places it in a group. */
    public record Row(StoredProposal proposal, UUID opportunityId, OpportunityStatus opportunityStatus) {

        public InboxGroup group() {
            return InboxGroup.of(opportunityStatus);
        }
    }

    private final JdbcClient jdbc;

    DecisionInbox(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Rows of {@code group}, newest proposal first, strictly after the keyset position ({@code beforeAt},
     * {@code beforeId}) when it is given. Returns at most {@code limit} rows.
     */
    public List<Row> page(TrustedStoreScope scope, InboxGroup group, Instant beforeAt, UUID beforeId, int limit) {
        String keyset = beforeAt == null ? "" : " AND (p.created_at, p.id) < (:beforeAt, :beforeId)";
        var statement = jdbc.sql(LATEST + " AND o.status IN (:statuses)" + keyset + """

                        ORDER BY p.created_at DESC, p.id DESC
                        LIMIT :limit
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("statuses", group.statuses().stream().map(Enum::name).toList())
                .param("limit", limit);
        if (beforeAt != null) {
            statement = statement.param("beforeAt", Timestamp.from(beforeAt)).param("beforeId", beforeId);
        }
        return statement.query((rs, row) -> new Row(
                new StoredProposal(ProposalJson.read(CanonicalJson.read(rs.getString("content"))),
                        rs.getString("payload_hash"), ProposalStatus.valueOf(rs.getString("status")),
                        rs.getTimestamp("status_changed_at").toInstant()),
                rs.getObject("opportunity_id", UUID.class),
                OpportunityStatus.valueOf(rs.getString("opportunity_status")))).list();
    }

    /** Row counts of every group, independent of any cursor. */
    public Map<InboxGroup, Long> counts(TrustedStoreScope scope) {
        Map<InboxGroup, Long> counts = new EnumMap<>(InboxGroup.class);
        for (InboxGroup group : InboxGroup.values()) {
            counts.put(group, 0L);
        }
        jdbc.sql("SELECT opportunity_status, count(*) AS n FROM (" + LATEST + ") latest GROUP BY opportunity_status")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(rs -> {
                    InboxGroup group = InboxGroup.of(OpportunityStatus.valueOf(rs.getString("opportunity_status")));
                    counts.merge(group, rs.getLong("n"), Long::sum);
                });
        return counts;
    }

    /** The latest revision of every episode of the store, with the episode status. */
    private static final String LATEST = """
            SELECT p.id, p.content, p.payload_hash, p.status, p.status_changed_at, p.created_at,
                   p.opportunity_id, o.status AS opportunity_status
            FROM decision_proposal p
            JOIN opportunity o ON o.id = p.opportunity_id AND o.store_id = p.store_id
            WHERE p.organization_id = :organizationId AND p.store_id = :storeId
              AND NOT EXISTS (SELECT 1 FROM decision_proposal newer
                              WHERE newer.opportunity_id = p.opportunity_id AND newer.store_id = p.store_id
                                AND newer.revision > p.revision)""";
}
