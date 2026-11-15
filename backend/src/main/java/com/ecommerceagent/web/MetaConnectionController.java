package com.ecommerceagent.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.MetaConnection;
import com.ecommerceagent.integration.MetaConnectionStatus;
import com.ecommerceagent.integration.MetaConnections;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The store's Meta ad account connection (ADR-0017). The token goes in, never out. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/meta-connection")
class MetaConnectionController {

    private final MetaConnections connections;

    MetaConnectionController(MetaConnections connections) {
        this.connections = connections;
    }

    @GetMapping
    MetaConnectionEnvelope get(TrustedStoreScope scope) {
        return new MetaConnectionEnvelope(connections.find(scope).map(c -> MetaConnectionResponse.from(c, scope))
                .orElse(null), scope.has(StorePermission.STORE_MANAGE));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    MetaConnectionResponse create(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody CreateMetaConnectionRequest request) {
        return MetaConnectionResponse.from(connections.create(scope, request.accountId()), scope);
    }

    @PostMapping("/{connectionId}/token")
    MetaConnectionResponse token(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String connectionId, @RequestBody MetaTokenRequest request) {
        return MetaConnectionResponse.from(connections.saveToken(scope, id(connectionId), request.token()), scope);
    }

    @PostMapping("/{connectionId}/checks")
    MetaConnectionResponse check(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String connectionId) {
        return MetaConnectionResponse.from(connections.check(scope, id(connectionId)), scope);
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IntegrationNotFoundException(null);
        }
    }

    enum MetaConnectionAction {
        SAVE_TOKEN,
        RUN_CHECK
    }

    record CreateMetaConnectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "act_1234567890") String accountId) {
    }

    record MetaTokenRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String token) {
    }

    record MetaConnectionEnvelope(MetaConnectionResponse connection,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canCreate) {
    }

    record MetaConnectionResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Ad account id without act_")
            String accountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) MetaConnectionStatus status,
            @Schema(description = "Last 4 characters of the token") String tokenHint,
            String accountName,
            String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The token may pause ads")
            boolean canWrite,
            Instant lastCheckAt,
            Instant lastSuccessAt,
            String lastErrorCode,
            Instant lastSyncAt,
            String lastSyncError,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int adsSeen,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MetaConnectionAction> allowedActions) {

        static MetaConnectionResponse from(MetaConnection connection, TrustedStoreScope scope) {
            List<MetaConnectionAction> actions = new ArrayList<>();
            if (scope.has(StorePermission.STORE_MANAGE)) {
                actions.add(MetaConnectionAction.SAVE_TOKEN);
                if (connection.status() != MetaConnectionStatus.PENDING_CREDENTIALS) {
                    actions.add(MetaConnectionAction.RUN_CHECK);
                }
            }
            return new MetaConnectionResponse(connection.id(), connection.accountId(), connection.status(),
                    connection.tokenHint(), connection.accountName(), connection.currency(), connection.canWrite(),
                    connection.lastCheckAt(), connection.lastSuccessAt(), connection.lastErrorCode(),
                    connection.lastSyncAt(), connection.lastSyncError(), connection.adsSeen(), List.copyOf(actions));
        }
    }
}
