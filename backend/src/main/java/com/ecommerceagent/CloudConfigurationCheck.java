package com.ecommerceagent;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.ecommerceagent.automation.guardian.InventoryProtectionPolicy;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import com.ecommerceagent.aigovernance.ProviderRateLimits;
import com.ecommerceagent.intelligence.inventory.InventoryAgentRunPolicy;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.context.properties.bind.Binder;
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
        // ADR-0022/0023: the API and the worker never migrate and never hold the migration credentials.
        if (!"false".equalsIgnoreCase(value(environment, "spring.flyway.enabled"))) {
            problems.add("spring.flyway.enabled must be false; migrations run in the separate migration step");
        }
        for (String variable : List.of("DB_MIGRATION_USERNAME", "DB_MIGRATION_PASSWORD")) {
            String present = value(environment, variable);
            if (present != null && !present.isBlank()) {
                problems.add(variable + " must not be given to the API or the worker");
            }
        }
        if ("false".equalsIgnoreCase(value(environment, "operator.row-security.enabled"))) {
            problems.add("operator.row-security.enabled cannot be switched off");
        }
        // ADR-0026 point 4: L2 is not available in this version.
        if ("true".equalsIgnoreCase(value(environment, InventoryProtectionPolicy.PREFIX + ".l2-enabled"))) {
            problems.add(InventoryProtectionPolicy.PREFIX + ".l2-enabled cannot be switched on (L2 is not available)");
        }
        // ADR-0027 section 5: the output limit of a call moved into the run policy; the old key would be ignored.
        String movedKey = value(environment, "operator.agent.max-output-tokens-per-call");
        if (movedKey != null) {
            problems.add("operator.agent.max-output-tokens-per-call is no longer read; set "
                    + InventoryAgentRunPolicy.PREFIX + ".max-output-tokens-per-call with its own version");
        }
        // ADR-0027 section 9: prices come only from the catalog ai_price; a configured price list would be ignored.
        boolean priceList = environment.getPropertySources().stream()
                .filter(source -> source instanceof org.springframework.core.env.EnumerablePropertySource<?>)
                .flatMap(source -> java.util.Arrays.stream(
                        ((org.springframework.core.env.EnumerablePropertySource<?>) source).getPropertyNames()))
                .anyMatch(name -> name.startsWith("operator.agent.price-list"));
        if (priceList) {
            problems.add("operator.agent.price-list is no longer read; prices come from the catalog ai_price");
        }
        problems.addAll(parameterSetProblems(environment));
        return problems;
    }

    /**
     * ADR-0026 point 6: a parameter set that differs from its defaults must carry its own version, so that a
     * recorded version always names one set of values.
     */
    static List<String> parameterSetProblems(ConfigurableEnvironment environment) {
        List<String> problems = new ArrayList<>();
        Binder binder = Binder.get(environment);
        Binder defaults = new Binder(List.of());
        try {
            RiskDetectorSettings risk = binder.bindOrCreate(RiskDetectorSettings.PREFIX, RiskDetectorSettings.class);
            RiskDetectorSettings riskDefaults = defaults.bindOrCreate(RiskDetectorSettings.PREFIX,
                    RiskDetectorSettings.class);
            if (overridden(risk.parameters(), riskDefaults.parameters())
                    && risk.version().equals(riskDefaults.version())) {
                problems.add(RiskDetectorSettings.PREFIX + " differs from the defaults of " + riskDefaults.version()
                        + "; set its own version");
            }
            InventoryProtectionPolicy policy = binder.bindOrCreate(InventoryProtectionPolicy.PREFIX,
                    InventoryProtectionPolicy.class);
            InventoryProtectionPolicy policyDefaults = defaults.bindOrCreate(InventoryProtectionPolicy.PREFIX,
                    InventoryProtectionPolicy.class);
            if (overridden(policy.parameters(), policyDefaults.parameters())
                    && policy.version().equals(policyDefaults.version())) {
                problems.add(InventoryProtectionPolicy.PREFIX + " differs from the defaults of "
                        + policyDefaults.ref() + "; set its own version");
            }
            InventoryAgentRunPolicy run = binder.bindOrCreate(InventoryAgentRunPolicy.PREFIX,
                    InventoryAgentRunPolicy.class);
            InventoryAgentRunPolicy runDefaults = defaults.bindOrCreate(InventoryAgentRunPolicy.PREFIX,
                    InventoryAgentRunPolicy.class);
            if (overridden(run.parameters(), runDefaults.parameters()) && run.version().equals(runDefaults.version())) {
                problems.add(InventoryAgentRunPolicy.PREFIX + " differs from the defaults of " + runDefaults.version()
                        + "; set its own version");
            }
            ProviderRateLimits provider = binder.bindOrCreate(ProviderRateLimits.PREFIX, ProviderRateLimits.class);
            ProviderRateLimits providerDefaults = ProviderRateLimits.defaults();
            if (overridden(provider.parameters(), providerDefaults.parameters())
                    && provider.version().equals(providerDefaults.version())) {
                problems.add(ProviderRateLimits.PREFIX + " differs from the defaults of " + providerDefaults.version()
                        + "; set its own version");
            }
            ApiRateLimits api = binder.bindOrCreate(ApiRateLimits.PREFIX, ApiRateLimits.class);
            ApiRateLimits apiDefaults = ApiRateLimits.defaults();
            if (overridden(api.parameters(), apiDefaults.parameters()) && api.version().equals(apiDefaults.version())) {
                problems.add(ApiRateLimits.PREFIX + " differs from the defaults of " + apiDefaults.version()
                        + "; set its own version");
            }
        } catch (RuntimeException e) {
            problems.add("the parameter sets cannot be read: " + e.getMessage());
        }
        return problems;
    }

    private static boolean overridden(Map<String, String> actual, Map<String, String> defaults) {
        Map<String, String> a = new java.util.HashMap<>(actual);
        Map<String, String> d = new java.util.HashMap<>(defaults);
        a.remove("version");
        d.remove("version");
        return !a.equals(d);
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
