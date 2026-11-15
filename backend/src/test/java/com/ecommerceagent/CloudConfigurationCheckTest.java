package com.ecommerceagent;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * ADR-0023: the cloud profile never starts against a developer machine, with a local profile mixed in, with migrations
 * in the application or without row-level security.
 */
class CloudConfigurationCheckTest {

    @Test
    void otherProfilesAreNotChecked() {
        assertThat(CloudConfigurationCheck.problems(new MockEnvironment())).isEmpty();
    }

    @Test
    void aCompleteCloudConfigurationPasses() {
        assertThat(CloudConfigurationCheck.problems(cloud())).isEmpty();
    }

    @Test
    void localAddressesMissingValuesAndLocalProfilesAreRefused() {
        MockEnvironment environment = cloud()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/operator")
                .withProperty("spring.temporal.connection.target", "127.0.0.1:7233")
                .withProperty("operator.integration.secrets-key", "");
        environment.setActiveProfiles("cloud", "api", "local");

        assertThat(CloudConfigurationCheck.problems(environment)).containsExactly(
                "profile cloud cannot be combined with local, test or e2e",
                "spring.datasource.url points at a local address",
                "spring.temporal.connection.target points at a local address",
                "operator.integration.secrets-key is required");

        MockEnvironment missing = new MockEnvironment().withProperty("spring.datasource.url", "${DB_URL_NOT_SET}");
        // Missing: every address, the key, and the flyway switch.
        missing.setActiveProfiles("cloud");
        assertThat(CloudConfigurationCheck.problems(missing)).hasSize(CloudConfigurationCheck.ADDRESSES.size() + 2);
    }

    /** ADR-0022/0023: the API and the worker run as the runtime role and never migrate. */
    @Test
    void migrationsCredentialsAndSwitchedOffRowSecurityAreRefused() {
        MockEnvironment environment = cloud()
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("DB_MIGRATION_PASSWORD", "secret")
                .withProperty("operator.row-security.enabled", "false");

        assertThat(CloudConfigurationCheck.problems(environment)).containsExactly(
                "spring.flyway.enabled must be false; migrations run in the separate migration step",
                "DB_MIGRATION_PASSWORD must not be given to the API or the worker",
                "operator.row-security.enabled cannot be switched off");
    }

    /** ADR-0026: L2 cannot be switched on, and an overridden parameter set needs its own version. */
    @Test
    void l2AndUnversionedParameterOverridesAreRefused() {
        MockEnvironment environment = cloud()
                .withProperty("operator.policies.inventory-protection.l2-enabled", "true")
                .withProperty("operator.policies.inventory-protection.max-pauses-per-store-24h", "5")
                .withProperty("operator.inventory-guardian.risk.warning-days", "7");

        assertThat(CloudConfigurationCheck.problems(environment)).containsExactly(
                "operator.policies.inventory-protection.l2-enabled cannot be switched on (L2 is not available)",
                "operator.inventory-guardian.risk differs from the defaults of inventory-risk/2; set its own version",
                "operator.policies.inventory-protection differs from the defaults of inventory-protection@1.1.0;"
                        + " set its own version");

        MockEnvironment versioned = cloud()
                .withProperty("operator.policies.inventory-protection.max-pauses-per-store-24h", "2")
                .withProperty("operator.policies.inventory-protection.version", "1.1.0-store-trial")
                .withProperty("operator.inventory-guardian.risk.warning-days", "7")
                .withProperty("operator.inventory-guardian.risk.version", "inventory-risk/2001")
                .withProperty("operator.policies.inventory-protection.mode", "EXECUTE_ON_APPROVAL");
        assertThat(CloudConfigurationCheck.problems(versioned)).isEmpty();
    }

    /** ADR-0027 section 5: the run policy of the agent follows the same rule, and the moved key is refused. */
    @Test
    void anUnversionedRunPolicyOverrideAndTheMovedOutputKeyAreRefused() {
        MockEnvironment environment = cloud()
                .withProperty("operator.agent.run-policies.inventory-agent.max-run-cost-usd", "0.50")
                .withProperty("operator.agent.max-output-tokens-per-call", "3000");

        assertThat(CloudConfigurationCheck.problems(environment)).containsExactly(
                "operator.agent.max-output-tokens-per-call is no longer read; set"
                        + " operator.agent.run-policies.inventory-agent.max-output-tokens-per-call with its own version",
                "operator.agent.run-policies.inventory-agent differs from the defaults of inventory-agent-run/2;"
                        + " set its own version");

        MockEnvironment versioned = cloud()
                .withProperty("operator.agent.run-policies.inventory-agent.max-run-cost-usd", "0.05")
                .withProperty("operator.agent.run-policies.inventory-agent.version", "inventory-agent-run/3");
        assertThat(CloudConfigurationCheck.problems(versioned)).isEmpty();
    }

    /** ADR-0027 section 9: the catalog is the only price source, so a configured price list is refused. */
    @Test
    void aConfiguredPriceListIsRefused() {
        MockEnvironment environment = cloud()
                .withProperty("operator.agent.price-list.models[anthropic/claude-opus-5-5].input", "4.00");

        assertThat(CloudConfigurationCheck.problems(environment))
                .containsExactly("operator.agent.price-list is no longer read; prices come from the catalog ai_price");
    }

    /** ADR-0027 section 6.1: the API rate limits are a versioned parameter set too. */
    @Test
    void anUnversionedApiRateLimitOverrideIsRefused() {
        MockEnvironment environment = cloud().withProperty("operator.api-rate-limits.work-per-user-per-minute", "200");

        assertThat(CloudConfigurationCheck.problems(environment)).containsExactly(
                "operator.api-rate-limits differs from the defaults of api-rate-limits/1; set its own version");

        MockEnvironment versioned = cloud()
                .withProperty("operator.api-rate-limits.work-per-user-per-minute", "200")
                .withProperty("operator.api-rate-limits.version", "api-rate-limits/2");
        assertThat(CloudConfigurationCheck.problems(versioned)).isEmpty();
    }

    private static MockEnvironment cloud() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://db.internal.example:5432/operator")
                .withProperty("spring.temporal.connection.target", "eu-central-1.aws.api.temporal.io:7233")
                .withProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                        "https://id.example.eu/realms/operator")
                .withProperty("operator.integration.meta.graph-base-url", "https://graph.facebook.com")
                .withProperty("operator.notifications.app-base-url", "https://app.example.eu")
                .withProperty("operator.integration.webhooks.base-url", "https://api.example.eu")
                .withProperty("operator.integration.secrets-key", "c2VjcmV0")
                .withProperty("spring.flyway.enabled", "false");
        environment.setActiveProfiles("cloud", "api");
        return environment;
    }
}
