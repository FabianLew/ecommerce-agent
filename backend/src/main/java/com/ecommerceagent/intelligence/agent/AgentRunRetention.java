package com.ecommerceagent.intelligence.agent;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.identity.RetentionTask;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Redacts the content of old agent run steps: prompts, responses and tool results (ADR-0022 points 6 and 7, document 02
 * §Security). The run keeps its hashes, versions, token counts and cost, and the proposal it produced is untouched.
 * Runs of an episode that is not finished are left alone.
 */
@Component
class AgentRunRetention implements RetentionTask {

    private static final UUID NONE = new UUID(0, 0);

    private final JdbcClient jdbc;
    private final Opportunities opportunities;

    AgentRunRetention(JdbcClient jdbc, Opportunities opportunities) {
        this.jdbc = jdbc;
        this.opportunities = opportunities;
    }

    @Override
    public String name() {
        return "agent-run-content";
    }

    @Override
    public int apply(TrustedStoreScope scope, Instant cutoff, int batchSize) {
        // The content of an episode that is still open stays until the episode is finished (ADR-0022 point 7).
        List<UUID> unfinished = opportunities.unfinishedIds(scope);
        return jdbc.sql("""
                        UPDATE agent_run_step SET payload = '{"redacted": true}'::jsonb
                        WHERE (run_id, seq) IN (SELECT run_id, seq FROM agent_run_step
                                                WHERE organization_id = :organizationId
                                                  AND store_id = :storeId AND created_at < :cutoff
                                                  AND payload <> '{"redacted": true}'::jsonb
                                                  AND run_id NOT IN (SELECT id FROM agent_run
                                                                     WHERE organization_id = :organizationId
                                                                       AND store_id = :storeId
                                                                       AND opportunity_id IN (:unfinished))
                                                LIMIT :batch)
                        """)
                .param("unfinished", unfinished.isEmpty() ? List.of(NONE) : unfinished)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("cutoff", Timestamp.from(cutoff))
                .param("batch", batchSize)
                .update();
    }
}
