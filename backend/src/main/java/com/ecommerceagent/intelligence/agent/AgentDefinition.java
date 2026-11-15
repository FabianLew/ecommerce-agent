package com.ecommerceagent.intelligence.agent;

import java.util.Objects;
import java.util.Set;

/**
 * An agent profile (document 02 §3). It is code, so a file cannot widen the tools it may use.
 * Its run limits are a versioned policy passed with each run ({@link AgentRunRequest#limits()}).
 */
public record AgentDefinition(String name, String version, String systemPromptRef, Set<String> availableSkills,
        Set<String> allowedReadTools, String modelPolicy, String outputSchemaVersion) {

    public AgentDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(systemPromptRef, "systemPromptRef");
        availableSkills = Set.copyOf(availableSkills);
        allowedReadTools = Set.copyOf(allowedReadTools);
        Objects.requireNonNull(modelPolicy, "modelPolicy");
        Objects.requireNonNull(outputSchemaVersion, "outputSchemaVersion");
    }
}
