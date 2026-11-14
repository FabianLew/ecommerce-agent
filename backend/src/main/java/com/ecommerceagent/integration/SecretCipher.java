package com.ecommerceagent.integration;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM for integration credentials (ADR-0007). Each seal uses a fresh random 96-bit nonce; the
 * associated data binds the ciphertext to its connection, store and credential name.
 */
final class SecretCipher {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final String keyId;
    private final SecureRandom random = new SecureRandom();

    SecretCipher(String base64Key, String keyId) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(
                    "operator.integration.secrets-key (INTEGRATION_SECRETS_KEY) is required: base64 of 32 random bytes");
        }
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64Key.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("operator.integration.secrets-key must be base64", e);
        }
        if (raw.length != 32) {
            throw new IllegalStateException("operator.integration.secrets-key must decode to 32 bytes (AES-256)");
        }
        this.key = new SecretKeySpec(raw, "AES");
        this.keyId = keyId;
    }

    String keyId() {
        return keyId;
    }

    Sealed seal(String plaintext, String associatedData) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, nonce, associatedData);
            return new Sealed(cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), nonce, keyId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    String open(Sealed sealed, String associatedData) {
        if (!keyId.equals(sealed.keyId())) {
            throw new IllegalStateException("Credential was sealed with unknown key " + sealed.keyId());
        }
        try {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, sealed.nonce(), associatedData);
            return new String(cipher.doFinal(sealed.ciphertext()), StandardCharsets.UTF_8);
        } catch (AEADBadTagException e) {
            throw new IllegalStateException("Credential failed authentication (tampered or moved)", e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String associatedData) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(associatedData.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    record Sealed(byte[] ciphertext, byte[] nonce, String keyId) {
    }
}
