package com.ecommerceagent.intelligence.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/** Run records in memory, for runtime tests without a database. */
public final class InMemoryAgentRuns extends AgentRuns {

    public final Map<UUID, Start> started = new LinkedHashMap<>();
    public final Map<UUID, Finish> finished = new LinkedHashMap<>();
    public final Map<UUID, List<AgentRunStep>> steps = new LinkedHashMap<>();

    public InMemoryAgentRuns() {
        super(null);
    }

    @Override
    public void start(TrustedStoreScope scope, Start run) {
        started.put(run.id(), run);
    }

    @Override
    public void step(TrustedStoreScope scope, UUID runId, AgentRunStep step) {
        steps.computeIfAbsent(runId, key -> new ArrayList<>()).add(step);
    }

    @Override
    public void finish(TrustedStoreScope scope, UUID runId, Finish finish) {
        finished.put(runId, finish);
    }

    @Override
    public List<AgentRunStep> steps(TrustedStoreScope scope, UUID runId) {
        return steps.getOrDefault(runId, List.of());
    }

    @Override
    public List<AgentRun> attempts(TrustedStoreScope scope, String analysisKey) {
        return started.values().stream().filter(run -> run.analysisKey().equals(analysisKey)).map(this::run)
                .sorted(java.util.Comparator.comparingInt(AgentRun::attempt)).toList();
    }

    @Override
    public void markRejected(TrustedStoreScope scope, UUID runId, String codes) {
        Finish done = finished.get(runId);
        finished.put(runId, new Finish(AgentRunStatus.OUTPUT_REJECTED, codes, done.modelCalls(), done.toolCalls(),
                done.repairCalls(), done.inputTokens(), done.outputTokens(), done.usageComplete(),
                done.estimatedCostUsd(), done.output(), done.finishedAt()));
    }

    public AgentRun run(Start start) {
        Finish done = finished.get(start.id());
        return new AgentRun(start.id(), start.analysisKey(), start.attempt(), start.opportunityId(),
                start.contextSnapshotId(), start.agent().name(), start.agent().version(), start.skillRef(),
                start.knowledgeHash(), start.promptHash(), start.toolsHash(), start.agent().outputSchemaVersion(),
                start.agent().modelPolicy(), start.provider(), start.model(), start.priceList(),
                done == null ? AgentRunStatus.RUNNING : done.status(), done == null ? null : done.stopReason(),
                done == null ? 0 : done.modelCalls(), done == null ? 0 : done.toolCalls(),
                done == null ? 0 : done.repairCalls(), done == null ? 0 : done.inputTokens(),
                done == null ? 0 : done.outputTokens(), done == null || done.usageComplete(),
                done == null ? java.math.BigDecimal.ZERO : done.estimatedCostUsd(), done == null ? null : done.output(),
                start.startedAt(), done == null ? null : done.finishedAt());
    }

    public AgentRun only() {
        if (started.size() != 1) {
            throw new IllegalStateException("Runs: " + started.size());
        }
        return run(started.values().iterator().next());
    }

    @Override
    public Optional<AgentRun> find(TrustedStoreScope scope, UUID runId) {
        return Optional.empty();
    }
}
