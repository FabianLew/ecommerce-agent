package com.ecommerceagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The Graph API host rule (ADR-0017 point 3): a stub base URL only in the local and test profiles. */
class MetaHostRuleTest {

    static MetaProperties properties(String baseUrl) {
        return new MetaProperties(URI.create(baseUrl), "v23.0", Duration.ofSeconds(1), Duration.ofSeconds(1), 1000,
                Duration.ofSeconds(30), Duration.ofSeconds(60));
    }

    @Test
    void onlyMetasHostOverHttpsCountsAsTheMetaHost() {
        assertThat(properties("https://graph.facebook.com").usesMetaHost()).isTrue();
        assertThat(properties("https://graph.facebook.com/").usesMetaHost()).isTrue();
        assertThat(properties("http://graph.facebook.com").usesMetaHost()).isFalse();
        assertThat(properties("https://graph.facebook.com:8443").usesMetaHost()).isFalse();
        assertThat(properties("https://graph.facebook.com.evil.example").usesMetaHost()).isFalse();
        assertThat(properties("https://graph.facebook.com/v1/proxy").usesMetaHost()).isFalse();
    }

    @Test
    void aStubBaseUrlIsRefusedOutsideLocalAndTest() {
        IntegrationConfiguration configuration = new IntegrationConfiguration();
        MockEnvironment production = new MockEnvironment();
        production.setActiveProfiles("api");
        assertThatThrownBy(() -> configuration.metaGraphClient(properties("http://127.0.0.1:9999"), production))
                .isInstanceOf(IllegalStateException.class);
        assertThat(configuration.metaGraphClient(properties("https://graph.facebook.com"), production).base())
                .hasToString("https://graph.facebook.com/v23.0/");

        MockEnvironment local = new MockEnvironment();
        local.setActiveProfiles("local", "api");
        assertThat(configuration.metaGraphClient(properties("http://127.0.0.1:9999"), local).base())
                .hasToString("http://127.0.0.1:9999/v23.0/");
    }

    @Test
    void theApiVersionIsPinnedToAVersionString() {
        assertThatThrownBy(() -> new MetaProperties(URI.create("https://graph.facebook.com"), "../me", null, null, 1,
                null, null)).isInstanceOf(IllegalStateException.class);
    }
}
