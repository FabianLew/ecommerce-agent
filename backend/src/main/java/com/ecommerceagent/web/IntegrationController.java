package com.ecommerceagent.web;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.automation.StoreSync;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.ConnectionCheck;
import com.ecommerceagent.integration.ConnectionCheckError;
import com.ecommerceagent.integration.ConnectionStatus;
import com.ecommerceagent.integration.ConnectionWarning;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.integration.IntegrationHealthService;
import com.ecommerceagent.integration.WooHistoryImport;
import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.InvalidIntegrationRequestException;
import com.ecommerceagent.integration.SyncRun;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import com.ecommerceagent.integration.WooWebhooks;
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
    private final WooCommerceImport imports;
    private final StoreSync sync;
    private final WooWebhooks webhooks;
    private final IntegrationHealthService health;
    private final WooHistoryImport history;

    IntegrationController(IntegrationConnections connections, WooCommerceImport imports, StoreSync sync,
            WooWebhooks webhooks, IntegrationHealthService health, WooHistoryImport history) {
        this.connections = connections;
        this.imports = imports;
        this.sync = sync;
        this.webhooks = webhooks;
        this.health = health;
        this.history = history;
    }

    @GetMapping("/integrations")
    ListResponse<IntegrationResponse> list(TrustedStoreScope scope) {
        return ListResponse.complete(connections.list(scope).stream()
                .map(connection -> response(connection, scope))
                .toList());
    }

    @GetMapping("/integrations/{integrationId}")
    IntegrationResponse get(TrustedStoreScope scope, @PathVariable String integrationId) {
        return response(connections.get(scope, id(integrationId)), scope);
    }

    @PostMapping("/integration-connections")
    @ResponseStatus(HttpStatus.CREATED)
    IntegrationResponse create(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody CreateConnectionRequest request) {
        return response(connections.create(scope, request.provider(), request.storeUrl()), scope);
    }

    @PostMapping("/integration-connections/{connectionId}/credentials")
    IntegrationResponse credentials(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String connectionId, @RequestBody WooCommerceCredentialsRequest request) {
        return response(connections.saveWooCommerceCredentials(scope, id(connectionId),
                request.consumerKey(), request.consumerSecret()), scope);
    }

    /** Shared webhook secret, generated in the browser and also entered in WooCommerce (ADR-0009). */
    @PostMapping("/integration-connections/{connectionId}/webhook-secret")
    IntegrationResponse webhookSecret(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String connectionId, @RequestBody WebhookSecretRequest request) {
        return response(webhooks.saveSecret(scope, id(connectionId), request.secret()), scope);
    }

    @RateLimited(value = RateLimited.Group.WORK, idempotent = true)
    @PostMapping("/integrations/{integrationId}/checks")
    CheckResponse check(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String integrationId) {
        UUID id = id(integrationId);
        ConnectionCheck check = connections.check(scope, id);
        return new CheckResponse(check.status(), check.error(), check.warnings(), check.sourceCurrency(),
                check.checkedAt(), response(connections.get(scope, id), scope));
    }

    /** Starts the initial import (14 days, ADR-0008); the job runs in the background. */
    @RateLimited(value = RateLimited.Group.WORK, idempotent = true)
    @PostMapping("/integrations/{integrationId}/sync-jobs")
    @ResponseStatus(HttpStatus.ACCEPTED)
    SyncJobResponse startSync(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @PathVariable String integrationId, @RequestBody SyncJobRequest request) {
        if (request.scope() != SyncScope.INITIAL_14_DAYS) {
            throw new InvalidIntegrationRequestException("scope", "REQUIRED", "Choose the import scope");
        }
        return SyncJobResponse.from(sync.startInitialImport(scope, id(integrationId)));
    }

    @GetMapping("/integrations/{integrationId}/sync-jobs/{jobId}")
    SyncJobResponse syncJob(TrustedStoreScope scope, @PathVariable String integrationId, @PathVariable String jobId) {
        SyncRun run = imports.run(scope, id(jobId));
        if (!run.connectionId().equals(id(integrationId))) {
            throw new IntegrationNotFoundException(run.id());
        }
        return SyncJobResponse.from(run);
    }

    private IntegrationResponse response(IntegrationConnection connection, TrustedStoreScope scope) {
        return IntegrationResponse.from(connection, imports.latestRun(scope, connection.id()).orElse(null),
                WebhookResponse.from(connection, webhooks.deliveryUrl(connection).orElse(null)),
                health.health(scope, connection), HistoryResponse.from(history.progress(scope, connection.id())),
                scope);
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

    /** Write-only, like the API key. */
    record WebhookSecretRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, accessMode = Schema.AccessMode.WRITE_ONLY) String secret) {

        @Override
        public String toString() {
            return "WebhookSecretRequest[<redacted>]";
        }
    }

    /** What the user enters in WooCommerce, and whether deliveries arrive. Never contains the secret. */
    record WebhookResponse(
            @Schema(description = "Delivery URL to enter in WooCommerce; null until the public API address is configured")
            String deliveryUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> topics,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String apiVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean secretConfigured,
            Instant secretUpdatedAt,
            Instant lastReceivedAt) {

        static WebhookResponse from(IntegrationConnection connection, String deliveryUrl) {
            return new WebhookResponse(deliveryUrl, WooWebhooks.TOPICS, "wp_api_v3",
                    connection.webhookSecretSetAt() != null, connection.webhookSecretSetAt(),
                    connection.lastWebhookAt());
        }
    }

    enum IntegrationAction {
        SAVE_CREDENTIALS,
        SAVE_WEBHOOK_SECRET,
        RUN_CHECK,
        START_SYNC
    }

    /** Only the initial window is started by a user; the order history follows in the background (ADR-0015). */
    enum SyncScope {
        INITIAL_14_DAYS
    }

    record SyncJobRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) SyncScope scope) {
    }

    record SyncJobResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SyncRunStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowFrom,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant windowUntil,
            Integer productsImported,
            Integer variantsImported,
            Integer ordersImported,
            Integer ordersExpected,
            String errorCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant startedAt,
            Instant finishedAt) {

        static SyncJobResponse from(SyncRun run) {
            return new SyncJobResponse(run.id(), run.status(), run.windowFrom(), run.windowUntil(),
                    run.productsImported(), run.variantsImported(), run.ordersImported(), run.ordersExpected(),
                    run.errorCode(), run.startedAt(), run.finishedAt());
        }
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
            SyncJobResponse lastSync,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WebhookResponse webhook,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HealthResponse health,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HistoryResponse history,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IntegrationAction> allowedActions) {

        static IntegrationResponse from(IntegrationConnection connection, SyncRun lastSync, WebhookResponse webhook,
                IntegrationHealth health, HistoryResponse history, TrustedStoreScope scope) {
            List<IntegrationAction> actions = new ArrayList<>();
            if (scope.has(StorePermission.STORE_MANAGE)) {
                actions.add(IntegrationAction.SAVE_CREDENTIALS);
                actions.add(IntegrationAction.SAVE_WEBHOOK_SECRET);
                if (connection.status() != ConnectionStatus.PENDING_CREDENTIALS) {
                    actions.add(IntegrationAction.RUN_CHECK);
                }
                boolean running = lastSync != null && lastSync.status() == SyncRunStatus.RUNNING;
                if (connection.status() == ConnectionStatus.CONNECTED && !running) {
                    actions.add(IntegrationAction.START_SYNC);
                }
            }
            return new IntegrationResponse(connection.id(), connection.provider(), connection.storeUrl().toString(),
                    connection.status(), connection.keyHint(), connection.lastCheckAt(), connection.lastSuccessAt(),
                    connection.lastErrorCode(), connection.lastCheckWarnings(), connection.sourceCurrency(),
                    lastSync == null ? null : SyncJobResponse.from(lastSync), webhook, HealthResponse.from(health),
                    history, List.copyOf(actions));
        }
    }

    /** The background order history import (ADR-0015); it never affects {@code health}. */
    record HistoryResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) WooHistoryImport.HistoryProgress.Status status,
            @Schema(description = "Orders are imported from this moment on") Instant importedFrom,
            @Schema(description = "How far back the history import goes") Instant target) {

        static HistoryResponse from(WooHistoryImport.HistoryProgress progress) {
            return new HistoryResponse(progress.status(), progress.importedFrom(), progress.target());
        }
    }

    /** Computed when read (ADR-0010). {@code STALE} blocks analysis that depends on current data. */
    record HealthResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) IntegrationHealth.Status status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<IntegrationHealth.Reason> reasons,
            Instant lastPollAt,
            Instant lastReconcileAt,
            @Schema(description = "Orders are known to be complete up to this moment") Instant ordersCompleteUntil,
            Instant lastWebhookAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int missedWebhooks24h) {

        static HealthResponse from(IntegrationHealth health) {
            return new HealthResponse(health.status(), health.reasons(), health.lastPollAt(),
                    health.lastReconcileAt(), health.ordersCompleteUntil(), health.lastWebhookAt(),
                    health.missedWebhooks24h());
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
