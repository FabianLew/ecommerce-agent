package com.ecommerceagent.integration.woocommerce;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.Map;

import org.junit.jupiter.api.Test;

class OAuth1SignerTest {

    /** Vector computed independently (Python, RFC 5849 base string) and accepted by WooCommerce in the live test. */
    @Test
    void signsLikeWooCommerce() {
        Map<String, String> parameters = Map.of(
                "per_page", "100",
                "after", "2026-11-01T00:00:00",
                "dates_are_gmt", "true",
                "oauth_consumer_key", "ck_test",
                "oauth_nonce", "abc123",
                "oauth_signature_method", "HMAC-SHA256",
                "oauth_timestamp", "1800000000");

        String signature = OAuth1Signer.sign("GET", URI.create("http://localhost:8081/wp-json/wc/v3/orders"),
                parameters, "cs_test");

        assertThat(signature).isEqualTo("Xur63DVVwPXIUVx1CLSdOfBoBGq94xnVXGa+D5F3cZM=");
    }

    @Test
    void encodesLikePhpRawurlencode() {
        assertThat(OAuth1Signer.encode("a b+c*~:/")).isEqualTo("a%20b%2Bc%2A~%3A%2F");
    }
}
