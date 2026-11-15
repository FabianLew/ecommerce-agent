package com.ecommerceagent.integration;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.woocommerce.StoreUrlPolicy.InvalidStoreUrlException;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import com.ecommerceagent.integration.woocommerce.WooCredentials;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Store integration connections (ADR-0007). Reads need {@code STORE_READ}; creating, changing credentials and
 * checking need {@code STORE_MANAGE}. Secrets are accepted here and never returned.
 */
@Service
public class IntegrationConnections {

    static final String CONSUMER_KEY = "woocommerce.consumer_key";
    static final String CONSUMER_SECRET = "woocommerce.consumer_secret";
    private static final String RESOURCE = "integration_connection";

    private final IntegrationConnectionRepository repository;
    private final SecretCipher cipher;
    private final WooCommerceClient client;
    private final WooConnectionChecker checker;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;

    IntegrationConnections(IntegrationConnectionRepository repository, SecretCipher cipher, WooCommerceClient client,
            WooConnectionChecker checker, StoreAccess storeAccess, AuditTrail audit) {
        this.repository = repository;
        this.cipher = cipher;
        this.client = client;
        this.checker = checker;
        this.storeAccess = storeAccess;
        this.audit = audit;
    }

    public List<IntegrationConnection> list(TrustedStoreScope scope) {
        return repository.list(scope);
    }

    public IntegrationConnection get(TrustedStoreScope scope, UUID connectionId) {
        return repository.find(scope, connectionId).orElseThrow(() -> new IntegrationNotFoundException(connectionId));
    }

    @Transactional
    public IntegrationConnection create(TrustedStoreScope scope, IntegrationProvider provider, String storeUrl) {
        scope.require(StorePermission.STORE_MANAGE);
        if (provider == null) {
            throw new InvalidIntegrationRequestException("provider", "REQUIRED", "Choose an integration provider");
        }
        URI url;
        try {
            url = client.policy().normalize(storeUrl);
        } catch (InvalidStoreUrlException e) {
            throw new InvalidIntegrationRequestException("storeUrl", e.code(), e.getMessage());
        }
        IntegrationConnection connection = repository.create(scope, provider, url)
                .orElseThrow(() -> new IntegrationConflictException(provider));
        audit.record(AuditEntry.inScope(scope, "INTEGRATION_CONNECTION_CREATED", RESOURCE, connection.id().toString(),
                Map.of("provider", provider.name(), "host", url.getHost())));
        return connection;
    }

    @Transactional
    public IntegrationConnection saveWooCommerceCredentials(TrustedStoreScope scope, UUID connectionId,
            String consumerKey, String consumerSecret) {
        WooCredentials credentials = new WooCredentials(
                requireKey("consumerKey", consumerKey, "ck_"), requireKey("consumerSecret", consumerSecret, "cs_"));
        // Storing a secret is sensitive: re-check the membership right before writing (ADR-0004).
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        IntegrationConnection connection = get(fresh, connectionId);
        if (connection.provider() != IntegrationProvider.WOOCOMMERCE) {
            throw new InvalidIntegrationRequestException("provider", "UNSUPPORTED", "Not a WooCommerce connection");
        }
        repository.saveCredential(fresh, connectionId, CONSUMER_KEY,
                cipher.seal(credentials.consumerKey(), associatedData(fresh, connectionId, CONSUMER_KEY)));
        repository.saveCredential(fresh, connectionId, CONSUMER_SECRET,
                cipher.seal(credentials.consumerSecret(), associatedData(fresh, connectionId, CONSUMER_SECRET)));
        repository.requireCheck(fresh, connectionId, credentials.keyHint());
        audit.record(AuditEntry.inScope(fresh, "INTEGRATION_CREDENTIALS_UPDATED", RESOURCE, connectionId.toString(),
                Map.of("provider", connection.provider().name(), "keyHint", credentials.keyHint())));
        return get(fresh, connectionId);
    }

    /** Runs the read-only check and stores its result. */
    public ConnectionCheck check(TrustedStoreScope scope, UUID connectionId) {
        scope.require(StorePermission.STORE_MANAGE);
        IntegrationConnection connection = get(scope, connectionId);
        WooCredentials credentials = wooCredentials(scope, connection);
        ConnectionCheck result = checker.check(connection.storeUrl(), credentials, repository.storeCurrency(scope));
        repository.recordCheck(scope, connectionId, result);
        audit.record(AuditEntry.inScope(scope, "INTEGRATION_CHECK_COMPLETED", RESOURCE, connectionId.toString(),
                Map.of("provider", connection.provider().name(), "status", result.status().name(),
                        "error", result.error() == null ? "" : result.error().name())));
        return result;
    }

    /** Decrypted credentials, for this module only (connection check, import in B-13). */
    WooCredentials wooCredentials(TrustedStoreScope scope, IntegrationConnection connection) {
        String key = open(scope, connection.id(), CONSUMER_KEY);
        String secret = open(scope, connection.id(), CONSUMER_SECRET);
        return new WooCredentials(key, secret);
    }

    private String open(TrustedStoreScope scope, UUID connectionId, String name) {
        SecretCipher.Sealed sealed = repository.credential(scope, connectionId, name)
                .orElseThrow(() -> new InvalidIntegrationRequestException("credentials", "CREDENTIALS_MISSING",
                        "Save the API key and secret first"));
        return openAndRefresh(repository, cipher, scope, connectionId, name, sealed);
    }

    /** Opens a stored value and, when it was sealed with a previous key, reseals it with the current one. */
    static String openAndRefresh(IntegrationConnectionRepository repository, SecretCipher cipher,
            TrustedStoreScope scope, UUID connectionId, String name, SecretCipher.Sealed sealed) {
        String associatedData = associatedData(scope, connectionId, name);
        String value = cipher.open(sealed, associatedData);
        if (!cipher.current(sealed)) {
            repository.reseal(scope, connectionId, name, cipher.seal(value, associatedData), sealed.keyId());
        }
        return value;
    }

    static String associatedData(TrustedStoreScope scope, UUID connectionId, String name) {
        return connectionId + "|" + scope.storeId() + "|" + name;
    }

    private static String requireKey(String field, String value, String prefix) {
        if (value == null || value.isBlank()) {
            throw new InvalidIntegrationRequestException(field, "REQUIRED", "This field is required");
        }
        String trimmed = value.strip();
        if (trimmed.length() > 200 || !trimmed.startsWith(prefix) || !trimmed.matches("[A-Za-z0-9_]+")) {
            throw new InvalidIntegrationRequestException(field, "KEY_FORMAT",
                    "WooCommerce values start with " + prefix + " and contain only letters, digits and _");
        }
        return trimmed;
    }
}
