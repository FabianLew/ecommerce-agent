package com.ecommerceagent.intelligence.model;

import java.util.Objects;

/** A tool definition as the model sees it. {@code inputSchema} is a JSON Schema document. */
public record ToolSpec(String name, String description, String inputSchema) {

    public ToolSpec {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(inputSchema, "inputSchema");
    }
}
