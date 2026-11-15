package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.knowledge.PromptPackage;
import com.ecommerceagent.intelligence.knowledge.SkillPackage;
import com.ecommerceagent.intelligence.model.ModelId;

/**
 * One attempt of one analysis. Everything here is set by the backend: the scope, the episode, the snapshot, the
 * pinned agent, skill and prompts, the model and the cost already spent by earlier attempts of the same analysis.
 */
public record AgentRunRequest(TrustedStoreScope scope, UUID opportunityId, ContextSnapshot snapshot,
        AgentDefinition agent, SkillPackage skill, PromptPackage prompts, String system, String task, ModelId model,
        String analysisKey, int attempt, BigDecimal costSpentBefore) {

    public AgentRunRequest {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(opportunityId, "opportunityId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(skill, "skill");
        Objects.requireNonNull(prompts, "prompts");
        Objects.requireNonNull(system, "system");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(analysisKey, "analysisKey");
        Objects.requireNonNull(costSpentBefore, "costSpentBefore");
    }
}
