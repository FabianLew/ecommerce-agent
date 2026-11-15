package com.ecommerceagent.intelligence.knowledge;

import java.util.Map;

/** The prompt templates of one agent ({@code system.md}, {@code task.md}) and their hash. */
public record PromptPackage(String agent, String hash, String system, String task) {

    /** Replaces {@code {{name}}} placeholders; an unknown placeholder is an error, so no template ships half filled. */
    public static String fill(String template, Map<String, String> values) {
        StringBuilder out = new StringBuilder();
        int at = 0;
        while (true) {
            int open = template.indexOf("{{", at);
            if (open < 0) {
                out.append(template, at, template.length());
                return out.toString();
            }
            int close = template.indexOf("}}", open);
            if (close < 0) {
                throw new IllegalArgumentException("Unclosed placeholder");
            }
            String key = template.substring(open + 2, close);
            String value = values.get(key);
            if (value == null) {
                throw new IllegalArgumentException("No value for placeholder " + key);
            }
            out.append(template, at, open).append(value);
            at = close + 2;
        }
    }
}
