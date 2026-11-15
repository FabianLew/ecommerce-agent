package com.ecommerceagent.automation.execution;

import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.inventory.EpisodeActionHistory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** The ledger's view of an episode's executions for the snapshot builder (ADR-0016 point 4). */
@Component
class LedgerActionHistory implements EpisodeActionHistory {

    private final JdbcClient jdbc;

    LedgerActionHistory(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ExecutedAction> executed(TrustedStoreScope scope, List<UUID> proposalIds) {
        if (proposalIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        SELECT a.proposal_id, a.revision, a.action_type, a.target_external_id, e.status, e.updated_at
                        FROM action_execution e
                        JOIN authorized_action a ON a.id = e.authorized_action_id
                        WHERE e.organization_id = :organizationId AND e.store_id = :storeId
                          AND a.organization_id = :organizationId AND a.store_id = :storeId
                          AND a.proposal_id IN (:ids)
                        ORDER BY e.created_at, e.id
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("ids", proposalIds)
                .query((rs, n) -> new ExecutedAction(rs.getObject("proposal_id", UUID.class), rs.getInt("revision"),
                        rs.getString("action_type"), rs.getString("target_external_id"), rs.getString("status"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }
}
