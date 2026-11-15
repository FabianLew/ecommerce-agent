package com.ecommerceagent.intelligence.agent;

import java.sql.Timestamp;
import java.time.Instant;

import com.ecommerceagent.identity.RetentionTask;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Redacts the content of old agent run steps: prompts, responses and tool results (ADR-0022 point 5, document 02
 * §Security). The run keeps its hashes, versions, token counts and cost, and the proposal it produced is untouched.
 */
@Component
class AgentRunRetention implements RetentionTask {

    private final JdbcClient jdbc;

    AgentRunRetention(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String name() {
        return "agent-run-content";
    }

    @Override
    public int apply(Instant cutoff, int batchSize) {
        return jdbc.sql("""
                        UPDATE agent_run_step SET payload = '{"redacted": true}'::jsonb
                        WHERE (run_id, seq) IN (SELECT run_id, seq FROM agent_run_step
                                                WHERE created_at < :cutoff
                                                  AND payload <> '{"redacted": true}'::jsonb
                                                LIMIT :batch)
                        """)
                .param("cutoff", Timestamp.from(cutoff))
                .param("batch", batchSize)
                .update();
    }
}
