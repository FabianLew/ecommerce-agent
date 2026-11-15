package com.ecommerceagent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.junit.jupiter.api.Test;

/** Runtime safety (ADR-0007): sealed credentials are bound to their connection, store and name. */
class SecretCipherTest {

    static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    final SecretCipher cipher = new SecretCipher(KEY, "test-v1");

    @Test
    void roundTripsWithFreshNonce() {
        SecretCipher.Sealed first = cipher.seal("cs_secret", "conn|store|secret");
        SecretCipher.Sealed second = cipher.seal("cs_secret", "conn|store|secret");

        assertThat(cipher.open(first, "conn|store|secret")).isEqualTo("cs_secret");
        assertThat(first.nonce()).isNotEqualTo(second.nonce());
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(new String(first.ciphertext(), StandardCharsets.ISO_8859_1)).doesNotContain("cs_secret");
    }

    @Test
    void ciphertextCannotBeMovedToAnotherConnectionOrStore() {
        SecretCipher.Sealed sealed = cipher.seal("cs_secret", "conn-a|store-a|secret");

        assertThatThrownBy(() -> cipher.open(sealed, "conn-b|store-a|secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(sealed, "conn-a|store-b|secret")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(sealed, "conn-a|store-a|key")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tamperingIsDetected() {
        SecretCipher.Sealed sealed = cipher.seal("cs_secret", "aad");
        byte[] tampered = sealed.ciphertext().clone();
        tampered[0] ^= 1;

        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed(tampered, sealed.nonce(), "test-v1"), "aad"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed(sealed.ciphertext(), new byte[12], "test-v1"), "aad"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cipher.open(new SecretCipher.Sealed(sealed.ciphertext(), sealed.nonce(), "other"), "aad"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void refusesMissingOrWeakKeys() {
        assertThatThrownBy(() -> new SecretCipher("", "v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(null, "v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher("not base64!", "v1")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(Base64.getEncoder().encodeToString(new byte[16]), "v1"))
                .isInstanceOf(IllegalStateException.class);
    }

    /** ADR-0022 point 4: a rotation keeps old values readable and seals new ones with the current key only. */
    @Test
    void opensValuesOfPreviousKeysAndSealsWithTheCurrentOne() {
        byte[] other = new byte[32];
        other[0] = 7;
        String newKey = Base64.getEncoder().encodeToString(other);
        SecretCipher.Sealed old = cipher.seal("cs_secret", "aad");
        SecretCipher rotated = new SecretCipher(newKey, "test-v2", java.util.Map.of("test-v1", KEY));

        assertThat(rotated.open(old, "aad")).isEqualTo("cs_secret");
        assertThat(rotated.current(old)).isFalse();
        SecretCipher.Sealed resealed = rotated.seal("cs_secret", "aad");
        assertThat(resealed.keyId()).isEqualTo("test-v2");
        assertThat(rotated.current(resealed)).isTrue();
        assertThatThrownBy(() -> cipher.open(resealed, "aad")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown key");
        assertThatThrownBy(() -> new SecretCipher(newKey, "test-v1", java.util.Map.of("test-v1", KEY)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new SecretCipher(newKey, "test-v2", java.util.Map.of("test-v1", "short")))
                .isInstanceOf(IllegalStateException.class);
    }
}
