package com.ecommerceagent.intelligence.agent;

import java.util.Objects;

import com.ecommerceagent.intelligence.model.ToolSpec;
import tools.jackson.databind.JsonNode;

/** A registered tool: name, version, description, closed input schema, effect and result size limit. */
public record ToolDefinition(String name, String version, String description, JsonNode inputSchema, ToolEffect effect,
        int maxResultBytes) {

    public ToolDefinition {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
        Objects.requireNonNull(effect, "effect");
        if (!inputSchema.path("additionalProperties").isBoolean()
                || inputSchema.path("additionalProperties").asBoolean()) {
            throw new IllegalArgumentException("Tool " + name + " must close its schema (additionalProperties: false)");
        }
    }

    public ToolSpec spec() {
        return new ToolSpec(name, description, com.ecommerceagent.metrics.CanonicalJson.write(inputSchema));
    }
}
