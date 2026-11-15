package com.ecommerceagent.intelligence.model;

/** What the runtime may rely on for a model (document 02 §4, capability matrix). */
public record ModelCapabilities(boolean toolCalling, boolean nativeStructuredOutput, int maxInputTokens,
        int maxOutputTokens) {
}
