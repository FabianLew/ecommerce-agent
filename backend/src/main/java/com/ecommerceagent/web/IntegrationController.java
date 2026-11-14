package com.ecommerceagent.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.ConnectionCheck;
import com.ecommerceagent.integration.ConnectionCheckError;
import com.ecommerceagent.integration.ConnectionStatus;
import com.ecommerceagent.integration.ConnectionWarning;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.IntegrationProvider;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Integrations screen (04 §10, ADR-0007). Secrets go in, never out. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}")
class IntegrationController {

    private final IntegrationConnections connections;

    IntegrationController(IntegrationConnections connections) {
        this.connections = connections;
    }

    @GetMapping("/integrations")
    ListResponse<IntegrationResponse> list(TrustedStoreScope scope) {
        return ListResponse.complete(connections.list(scope).stream()
                .map(connection -> IntegrationResponse.from(connection, scope))
                .toList());
    }

    @GetMapping("/integrations/{integrationId}")
    IntegrationResponse get(TrustedStoreScope scope, @PathVariable String integrationId) {
        return IntegrationResponse.from(connections.get(scope, id(integrationId)), scope);
    }

    @PostMapping("/integration-connections")
    @ResponseStatus(HttpStatus.CREATED)
    IntegrationResponse create(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody CreateConnectionRequest request) {
        return IntegrationResponse.from(connections.create(scope, request.provider(), request.storeUrl()), scope);
    }

    @PostMapping("/integration-connections/{connectionId}/credentials")
    IntegrationResponse credentials(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String connectionId, @RequestBody WooCommerceCredentialsRequest request) {
        return IntegrationResponse.from(connections.saveWooCommerceCredentials(scope, id(connectionId),
                request.consumerKey(), request.consumerSecret()), scope);
    }

    @PostMapping("/integrations/{integrationId}/checks")
    CheckResponse check(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String integrationId) {
        UUID id = id(integrationId);
        ConnectionCheck check = connections.check(scope, id);
        return new CheckResponse(check.status(), check.error(), check.warnings(), check.sourceCurrency(),
                check.checkedAt(), IntegrationResponse.from(connections.get(scope, id), scope));
    }

    /** A malformed id is reported like a missing one, as for store ids. */
    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new IntegrationNotFoundException(null);
        }
    }

    record CreateConnectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IntegrationProvider provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, example = "https://shop.example.com") String storeUrl) {
    }

    /** Values are write-only; {@link #toString()} never prints them. */
    record WooCommerceCredentialsRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) String consumerKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) String consumerSecret) {

        @Override
        public String toString() {
            return "WooCommerceCredentialsRequest[<redacted>]";
        }
    }

    enum IntegrationAction {
        SAVE_CREDENTIALS,
        RUN_CHECK
    }

    record IntegrationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IntegrationProvider provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String storeUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ConnectionStatus status,
            @Schema(description = "Last 4 characters of the API key") String keyHint,
            Instant lastCheckAt,
            Instant lastSuccessAt,
            ConnectionCheckError lastErrorCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ConnectionWarning> warnings,
            String sourceCurrency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IntegrationAction> allowedActions) {

        static IntegrationResponse from(IntegrationConnection connection, TrustedStoreScope scope) {
            List<IntegrationAction> actions = new ArrayList<>();
            if (scope.has(StorePermission.STORE_MANAGE)) {
                actions.add(IntegrationAction.SAVE_CREDENTIALS);
                if (connection.status() != ConnectionStatus.PENDING_CREDENTIALS) {
                    actions.add(IntegrationAction.RUN_CHECK);
                }
            }
            return new IntegrationResponse(connection.id(), connection.provider(), connection.storeUrl().toString(),
                    connection.status(), connection.keyHint(), connection.lastCheckAt(), connection.lastSuccessAt(),
                    connection.lastErrorCode(), connection.lastCheckWarnings(), connection.sourceCurrency(),
                    List.copyOf(actions));
        }
    }

    record CheckResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ConnectionStatus status,
            ConnectionCheckError errorCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ConnectionWarning> warnings,
            String sourceCurrency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant checkedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IntegrationResponse integration) {
    }
}
