package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.JsonNode;

/** Agent runs and steps of a store (ADR-0016 point 6). Every query is filtered by the trusted scope. */
@Repository
public class AgentRuns {

    private static final String SELECT = """
            SELECT id, analysis_key, attempt, opportunity_id, context_snapshot_id, agent_name, agent_version,
                   skill_ref, knowledge_hash, prompt_hash, tools_hash, output_schema_version, model_policy, provider,
                   model, price_list, status, stop_reason, model_calls, tool_calls, repair_calls, input_tokens,
                   output_tokens, usage_complete, estimated_cost_usd, output, started_at, finished_at
            FROM agent_run
            WHERE organization_id = :organizationId AND store_id = :storeId
            """;

    private final JdbcClient jdbc;

    private final MeterRegistry meters;

    AgentRuns(JdbcClient jdbc, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.meters = meters;
    }

    /** What a new run pins before its first model call. */
    public record Start(UUID id, String analysisKey, int attempt, UUID opportunityId, UUID contextSnapshotId,
            AgentDefinition agent, String skillRef, String knowledgeHash, String promptHash, String toolsHash,
            String provider, String model, String priceList, Instant startedAt) {
    }

    /** Counters and the end of a run. */
    public record Finish(AgentRunStatus status, String stopReason, int modelCalls, int toolCalls, int repairCalls,
            int inputTokens, int outputTokens, boolean usageComplete, BigDecimal estimatedCostUsd, JsonNode output,
            Instant finishedAt) {
    }

    public void start(TrustedStoreScope scope, Start run) {
        jdbc.sql("""
                        INSERT INTO agent_run (id, organization_id, store_id, analysis_key, attempt, opportunity_id,
                                               context_snapshot_id, agent_name, agent_version, skill_ref,
                                               knowledge_hash, prompt_hash, tools_hash, output_schema_version,
                                               model_policy, provider, model, price_list, status, started_at)
                        VALUES (:id, :organizationId, :storeId, :analysisKey, :attempt, :opportunityId, :snapshotId,
                                :agentName, :agentVersion, :skillRef, :knowledgeHash, :promptHash, :toolsHash,
                                :schemaVersion, :modelPolicy, :provider, :model, :priceList, 'RUNNING', :startedAt)
                        """)
                .param("id", run.id())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("analysisKey", run.analysisKey())
                .param("attempt", run.attempt())
                .param("opportunityId", run.opportunityId())
                .param("snapshotId", run.contextSnapshotId())
                .param("agentName", run.agent().name())
                .param("agentVersion", run.agent().version())
                .param("skillRef", run.skillRef())
                .param("knowledgeHash", run.knowledgeHash())
                .param("promptHash", run.promptHash())
                .param("toolsHash", run.toolsHash())
                .param("schemaVersion", run.agent().outputSchemaVersion())
                .param("modelPolicy", run.agent().modelPolicy())
                .param("provider", run.provider())
                .param("model", run.model())
                .param("priceList", run.priceList())
                .param("startedAt", Timestamp.from(run.startedAt()))
                .update();
    }

    public void step(TrustedStoreScope scope, UUID runId, AgentRunStep step) {
        jdbc.sql("""
                        INSERT INTO agent_run_step (run_id, organization_id, store_id, seq, kind, name, tool_call_id, status, error_code,
                                                    payload, duration_ms, created_at)
                        SELECT id, organization_id, store_id, :seq, :kind, :name, :toolCallId, :status, :errorCode,
                               CAST(:payload AS jsonb), :duration, :createdAt
                        FROM agent_run
                        WHERE id = :runId AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("seq", step.seq())
                .param("kind", step.kind().name())
                .param("name", step.name())
                .param("toolCallId", step.toolCallId())
                .param("status", step.status())
                .param("errorCode", step.errorCode())
                .param("payload", CanonicalJson.write(step.payload()))
                .param("duration", step.durationMillis())
                .param("createdAt", Timestamp.from(step.createdAt()))
                .param("runId", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    public void finish(TrustedStoreScope scope, UUID runId, Finish finish) {
        int finished = jdbc.sql("""
                        UPDATE agent_run SET status = :status, stop_reason = :stopReason, model_calls = :modelCalls,
                               tool_calls = :toolCalls, repair_calls = :repairCalls, input_tokens = :inputTokens,
                               output_tokens = :outputTokens, usage_complete = :usageComplete,
                               estimated_cost_usd = :cost, output = CAST(:output AS jsonb), finished_at = :finishedAt
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status = 'RUNNING'
                        """)
                .param("status", finish.status().name())
                .param("stopReason", finish.stopReason())
                .param("modelCalls", finish.modelCalls())
                .param("toolCalls", finish.toolCalls())
                .param("repairCalls", finish.repairCalls())
                .param("inputTokens", finish.inputTokens())
                .param("outputTokens", finish.outputTokens())
                .param("usageComplete", finish.usageComplete())
                .param("cost", finish.estimatedCostUsd())
                .param("output", finish.output() == null ? null : CanonicalJson.write(finish.output()))
                .param("finishedAt", Timestamp.from(finish.finishedAt()))
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
        if (finished == 1) {
            // ADR-0024: runs by status and their estimated cost, for the agent dashboard and the cost alert.
            meters.counter("operator.agent.runs", "status", finish.status().name()).increment();
            meters.summary("operator.agent.cost.usd").record(finish.estimatedCostUsd().doubleValue());
        }
    }

    /** A succeeded run whose proposal the validator rejected. */
    public void markRejected(TrustedStoreScope scope, UUID runId, String codes) {
        jdbc.sql("""
                        UPDATE agent_run SET status = 'OUTPUT_REJECTED', stop_reason = :codes
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                          AND status = 'SUCCEEDED'
                        """)
                .param("codes", codes)
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** Every attempt of one analysis, in attempt order. */
    public List<AgentRun> attempts(TrustedStoreScope scope, String analysisKey) {
        return jdbc.sql(SELECT + " AND analysis_key = :key ORDER BY attempt")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("key", analysisKey)
                .query(AgentRuns::map)
                .list();
    }

    public Optional<AgentRun> find(TrustedStoreScope scope, UUID runId) {
        return jdbc.sql(SELECT + " AND id = :id")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("id", runId)
                .query(AgentRuns::map)
                .optional();
    }

    public List<AgentRun> ofOpportunity(TrustedStoreScope scope, UUID opportunityId) {
        return jdbc.sql(SELECT + " AND opportunity_id = :opportunityId ORDER BY started_at, attempt")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("opportunityId", opportunityId)
                .query(AgentRuns::map)
                .list();
    }

    public List<AgentRunStep> steps(TrustedStoreScope scope, UUID runId) {
        return jdbc.sql("""
                        SELECT s.seq, s.kind, s.name, s.tool_call_id, s.status, s.error_code, s.payload, s.duration_ms,
                               s.created_at
                        FROM agent_run_step s JOIN agent_run r ON r.id = s.run_id AND r.store_id = s.store_id
                        WHERE s.run_id = :id AND s.organization_id = :organizationId AND s.store_id = :storeId
                        ORDER BY s.seq
                        """)
                .param("id", runId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, row) -> new AgentRunStep(rs.getInt("seq"), AgentRunStep.Kind.valueOf(rs.getString("kind")),
                        rs.getString("name"), rs.getString("tool_call_id"), rs.getString("status"),
                        rs.getString("error_code"), CanonicalJson.read(rs.getString("payload")),
                        rs.getLong("duration_ms"), rs.getTimestamp("created_at").toInstant()))
                .list();
    }

    private static AgentRun map(ResultSet rs, int row) throws SQLException {
        String output = rs.getString("output");
        Timestamp finished = rs.getTimestamp("finished_at");
        return new AgentRun(rs.getObject("id", UUID.class), rs.getString("analysis_key"), rs.getInt("attempt"),
                rs.getObject("opportunity_id", UUID.class), rs.getObject("context_snapshot_id", UUID.class),
                rs.getString("agent_name"), rs.getString("agent_version"), rs.getString("skill_ref"),
                rs.getString("knowledge_hash"), rs.getString("prompt_hash"), rs.getString("tools_hash"),
                rs.getString("output_schema_version"), rs.getString("model_policy"), rs.getString("provider"),
                rs.getString("model"), rs.getString("price_list"), AgentRunStatus.valueOf(rs.getString("status")),
                rs.getString("stop_reason"), rs.getInt("model_calls"), rs.getInt("tool_calls"),
                rs.getInt("repair_calls"), rs.getInt("input_tokens"), rs.getInt("output_tokens"),
                rs.getBoolean("usage_complete"), rs.getBigDecimal("estimated_cost_usd"),
                output == null ? null : CanonicalJson.read(output), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }
}
