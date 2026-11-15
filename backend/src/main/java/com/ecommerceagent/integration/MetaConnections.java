package com.ecommerceagent.integration;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.meta.MetaAccount;
import com.ecommerceagent.integration.meta.MetaApiException;
import com.ecommerceagent.integration.meta.MetaGraphClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * A store's Meta ad account connection (ADR-0017). Reads need {@code STORE_READ}; creating, saving the token and
 * checking need {@code STORE_MANAGE}. The token is accepted here and never returned.
 */
@Service
public class MetaConnections {

    static final String ACCESS_TOKEN = "meta.access_token";
    static final String READ_PERMISSION = "ads_read";
    static final String WRITE_PERMISSION = "ads_management";
    private static final String RESOURCE = "meta_connection";

    private final MetaConnectionRepository repository;
    private final SecretCipher cipher;
    private final MetaGraphClient client;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;
    private final Clock clock;

    MetaConnections(MetaConnectionRepository repository, SecretCipher cipher, MetaGraphClient client,
            StoreAccess storeAccess, AuditTrail audit, Clock clock) {
        this.repository = repository;
        this.cipher = cipher;
        this.client = client;
        this.storeAccess = storeAccess;
        this.audit = audit;
        this.clock = clock;
    }

    public Optional<MetaConnection> find(TrustedStoreScope scope) {
        return repository.find(scope);
    }

    public MetaConnection get(TrustedStoreScope scope, UUID connectionId) {
        return repository.find(scope).filter(connection -> connection.id().equals(connectionId))
                .orElseThrow(() -> new IntegrationNotFoundException(connectionId));
    }

    @Transactional
    public MetaConnection create(TrustedStoreScope scope, String accountId) {
        scope.require(StorePermission.STORE_MANAGE);
        String id = normalizeAccountId(accountId);
        MetaConnection connection = repository.create(scope, id)
                .orElseThrow(() -> new IntegrationConflictException("The store already has a Meta connection"));
        audit.record(AuditEntry.inScope(scope, "META_CONNECTION_CREATED", RESOURCE, connection.id().toString(),
                Map.of("accountId", id)));
        return connection;
    }

    @Transactional
    public MetaConnection saveToken(TrustedStoreScope scope, UUID connectionId, String token) {
        String value = requireToken(token);
        // Storing a secret is sensitive: re-check the membership right before writing (ADR-0004).
        TrustedStoreScope fresh = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        get(fresh, connectionId);
        String hint = value.substring(value.length() - 4);
        repository.saveToken(fresh, connectionId, ACCESS_TOKEN,
                cipher.seal(value, associatedData(fresh, connectionId, ACCESS_TOKEN)), hint);
        audit.record(AuditEntry.inScope(fresh, "META_TOKEN_UPDATED", RESOURCE, connectionId.toString(),
                Map.of("tokenHint", hint)));
        return get(fresh, connectionId);
    }

    /** Reads the account and the token's permissions and stores the result; never returns the raw response. */
    public MetaConnection check(TrustedStoreScope scope, UUID connectionId) {
        scope.require(StorePermission.STORE_MANAGE);
        MetaConnection connection = get(scope, connectionId);
        String token = token(scope, connection);
        Instant now = clock.instant();
        MetaConnectionStatus status;
        String error = null;
        String name = null;
        String currency = null;
        boolean canWrite = false;
        try {
            MetaAccount account = client.account(token, connection.accountId());
            Set<String> permissions = client.grantedPermissions(token);
            name = account.name();
            currency = account.currency();
            canWrite = permissions.contains(WRITE_PERMISSION);
            if (!permissions.contains(READ_PERMISSION) && !canWrite) {
                status = MetaConnectionStatus.ERROR;
                error = "PERMISSION_MISSING";
            } else {
                status = MetaConnectionStatus.CONNECTED;
            }
        } catch (MetaApiException e) {
            status = MetaConnectionStatus.ERROR;
            error = e.kind() == MetaApiException.Kind.NOT_FOUND ? "ACCOUNT_NOT_FOUND" : e.kind().name();
        }
        repository.recordCheck(scope, connectionId, status, error, name, currency, canWrite, now);
        audit.record(AuditEntry.inScope(scope, "META_CHECK_COMPLETED", RESOURCE, connectionId.toString(),
                Map.of("status", status.name(), "error", error == null ? "" : error, "canWrite",
                        Boolean.toString(canWrite))));
        return get(scope, connectionId);
    }

    /** The decrypted token, for this module only (polling, the pause in B-39). */
    String token(TrustedStoreScope scope, MetaConnection connection) {
        SecretCipher.Sealed sealed = repository.credential(scope, connection.id(), ACCESS_TOKEN)
                .orElseThrow(() -> new InvalidIntegrationRequestException("token", "CREDENTIALS_MISSING",
                        "Save the access token first"));
        return cipher.open(sealed, associatedData(scope, connection.id(), ACCESS_TOKEN));
    }

    static String associatedData(TrustedStoreScope scope, UUID connectionId, String name) {
        return "meta|" + connectionId + "|" + scope.storeId() + "|" + name;
    }

    static String normalizeAccountId(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            throw new InvalidIntegrationRequestException("accountId", "REQUIRED", "This field is required");
        }
        String value = accountId.strip();
        if (value.startsWith("act_")) {
            value = value.substring(4);
        }
        if (!value.matches("[0-9]{1,30}")) {
            throw new InvalidIntegrationRequestException("accountId", "ACCOUNT_ID_FORMAT",
                    "The ad account id is a number, optionally with the act_ prefix");
        }
        return value;
    }

    private static String requireToken(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidIntegrationRequestException("token", "REQUIRED", "This field is required");
        }
        String value = token.strip();
        if (value.length() < 20 || value.length() > 1024 || !value.matches("[A-Za-z0-9_\\-.|]+")) {
            throw new InvalidIntegrationRequestException("token", "TOKEN_FORMAT",
                    "Paste the system user access token from Business Manager");
        }
        return value;
    }
}
