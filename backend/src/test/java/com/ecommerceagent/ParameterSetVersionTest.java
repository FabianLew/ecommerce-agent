package com.ecommerceagent;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.ecommerceagent.automation.guardian.InventoryProtectionPolicy;
import com.ecommerceagent.decisions.RiskDetectorSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;

/**
 * ADR-0026 point 6: the defaults of each parameter set are pinned to their version. Changing a default without a
 * new version fails here; with a new version, add its hash below (the old versions stay in recorded data).
 */
class ParameterSetVersionTest {

    static final Map<String, String> PINNED = Map.of(
            "inventory-risk/2",
            "e0ec74d8372c9feacf426b342a81012478f6cf132e07a8ec6d3ff99285fc573c",
            "inventory-protection@1.1.0",
            "ff9b88ec7c30fc6fd17603a85728dd49dcf67a20b6e66f18ca3ebf5797b6c11c");

    @Test
    void theDefaultsOfTheDetectorMatchTheirVersion() {
        RiskDetectorSettings defaults = new Binder(List.of()).bindOrCreate(RiskDetectorSettings.PREFIX,
                RiskDetectorSettings.class);
        assertThat(defaults.version()).isEqualTo(RiskDetectorSettings.DEFAULT_VERSION);
        assertThat(hash(defaults.parameters())).as("hash of %s", defaults.version())
                .isEqualTo(PINNED.get(defaults.version()));
    }

    @Test
    void theDefaultsOfThePolicyMatchTheirVersion() {
        InventoryProtectionPolicy defaults = new Binder(List.of()).bindOrCreate(InventoryProtectionPolicy.PREFIX,
                InventoryProtectionPolicy.class);
        assertThat(defaults.version()).isEqualTo(InventoryProtectionPolicy.DEFAULT_VERSION);
        assertThat(hash(defaults.parameters())).as("hash of %s", defaults.ref())
                .isEqualTo(PINNED.get(defaults.ref()));
    }

    /** SHA-256 of the parameters in their fixed order, as {@code key=value} lines. */
    static String hash(Map<String, String> parameters) {
        StringBuilder text = new StringBuilder();
        parameters.forEach((key, value) -> text.append(key).append('=').append(value).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
