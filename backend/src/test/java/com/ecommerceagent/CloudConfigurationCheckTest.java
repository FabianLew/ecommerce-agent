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
