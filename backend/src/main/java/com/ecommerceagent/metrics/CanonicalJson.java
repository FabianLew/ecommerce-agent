package com.ecommerceagent.metrics;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Canonical JSON of evidence, snapshots and proposals (ADR-0011): object keys in lexicographic order, no
 * insignificant whitespace, integers as numbers, decimals and times as strings. Floating-point and decimal
 * numbers are rejected, because their text form is not stable across libraries.
 */
public final class CanonicalJson {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private CanonicalJson() {
    }

    public static String write(JsonNode node) {
        StringBuilder out = new StringBuilder();
        append(node, out);
        return out.toString();
    }

    /** {@code sha256:} followed by the lowercase hex SHA-256 of the canonical UTF-8 bytes. */
    public static String hash(JsonNode node) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(write(node).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static JsonNode read(String json) {
        return JSON.readTree(json);
    }

    public static JsonNode valueToTree(Object value) {
        return JSON.valueToTree(value);
    }

    private static void append(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            List<Map.Entry<String, JsonNode>> fields = new ArrayList<>(node.properties());
            fields.sort(Map.Entry.comparingByKey());
            out.append('{');
            for (int i = 0; i < fields.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append(JSON.writeValueAsString(fields.get(i).getKey())).append(':');
                append(fields.get(i).getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                append(node.get(i), out);
            }
            out.append(']');
        } else if (node.isString()) {
            out.append(JSON.writeValueAsString(node.stringValue()));
        } else if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue().toString());
        } else if (node.isNumber()) {
            throw new IllegalArgumentException("Decimal numbers must be strings in canonical JSON: " + node);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else {
            throw new IllegalArgumentException("Unsupported JSON node: " + node.getNodeType());
        }
    }
}
