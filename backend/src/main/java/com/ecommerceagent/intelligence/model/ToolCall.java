package com.ecommerceagent.intelligence.model;

import java.util.Objects;

/** A tool call as the model asked for it: an opaque provider id, a name and JSON arguments. */
public record ToolCall(String id, String name, String argumentsJson) {

    public ToolCall {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        argumentsJson = argumentsJson == null || argumentsJson.isBlank() ? "{}" : argumentsJson;
    }
}
