package com.ecommerceagent.intelligence.model;

import java.util.Objects;

/** A provider and its model name, e.g. {@code anthropic/claude-opus-5-5}. */
public record ModelId(String provider, String name) {

    public ModelId {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(name, "name");
    }

    public static ModelId parse(String value) {
        int slash = value.indexOf('/');
        if (slash <= 0 || slash == value.length() - 1) {
            throw new IllegalArgumentException("Model id must be provider/name: " + value);
        }
        return new ModelId(value.substring(0, slash), value.substring(slash + 1));
    }

    @Override
    public String toString() {
        return provider + "/" + name;
    }
}
