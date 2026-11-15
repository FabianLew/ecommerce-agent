package com.ecommerceagent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/**
 * Refuses to start the {@code cloud} profile (ADR-0023) with an address that points at a developer machine, a local
 * profile mixed in, or a stub for Meta. Runs before any bean exists, so nothing connects anywhere first.
 */
public class CloudConfigurationCheck implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

    static final Map<String, String> ADDRESSES = Map.of(
            "spring.datasource.url", "the database",
            "spring.temporal.connection.target", "Temporal",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri", "the identity provider",
            "operator.integration.meta.graph-base-url", "the Meta Graph API",
            "operator.notifications.app-base-url", "the web application",
            "operator.integration.webhooks.base-url", "the webhook address");

    @Override
    public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
        List<String> problems = problems(event.getEnvironment());
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Profile cloud refused: " + String.join("; ", problems));
        }
    }

    static List<String> problems(ConfigurableEnvironment environment) {
        List<String> problems = new ArrayList<>();
        if (!environment.acceptsProfiles(Profiles.of("cloud"))) {
            return problems;
        }
        if (environment.acceptsProfiles(Profiles.of("local", "test", "e2e"))) {
            problems.add("profile cloud cannot be combined with local, test or e2e");
        }
        ADDRESSES.keySet().stream().sorted().forEach(property -> {
            String value = value(environment, property);
            if (value == null || value.isBlank()) {
                problems.add(property + " is required");
            } else if (local(value)) {
                problems.add(property + " points at a local address");
            }
        });
        String key = value(environment, "operator.integration.secrets-key");
        if (key == null || key.isBlank()) {
            problems.add("operator.integration.secrets-key is required");
        }
        return problems;
    }

    /** The resolved value; a placeholder without an environment variable counts as missing. */
    private static String value(ConfigurableEnvironment environment, String property) {
        try {
            return environment.getProperty(property);
        } catch (IllegalArgumentException unresolved) {
            return null;
        }
    }

    private static boolean local(String value) {
        String host;
        try {
            String address = value.startsWith("jdbc:") ? value.substring("jdbc:".length()) : value;
            host = address.contains("://") ? URI.create(address).getHost() : address.split(":")[0];
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (host == null) {
            return false;
        }
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("localhost") || lower.startsWith("127.") || lower.equals("0.0.0.0")
                || lower.equals("[::1]") || lower.equals("::1") || lower.endsWith(".local")
                || lower.equals("host.docker.internal");
    }
}
