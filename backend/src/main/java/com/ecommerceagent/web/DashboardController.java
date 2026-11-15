package com.ecommerceagent.web;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.automation.Onboarding;
import com.ecommerceagent.decisions.DecisionInbox;
import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.InboxGroup;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.IntegrationConnection;
import com.ecommerceagent.integration.IntegrationConnections;
import com.ecommerceagent.integration.IntegrationHealth;
import com.ecommerceagent.integration.IntegrationHealthService;
import com.ecommerceagent.integration.IntegrationProvider;
import com.ecommerceagent.integration.MetaConnection;
import com.ecommerceagent.integration.MetaConnectionStatus;
import com.ecommerceagent.integration.MetaConnections;
import com.ecommerceagent.metrics.SalesMetrics;
import com.ecommerceagent.metrics.SalesMetrics.LocalWindow;
import com.ecommerceagent.metrics.SalesMetrics.SalesSummary;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dashboard v1 (04 §4): the last seven full local days against the seven before, what needs attention, recent
 * activity and integration health. Read only; every number comes from the same services as the detail screens.
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/dashboard")
class DashboardController {

    static final int DAYS = 7;
    static final int LIST_SIZE = 5;
    static final String META_SOURCE = "meta:insights";

    private final SalesMetrics sales;
    private final DecisionInbox inbox;
    private final IntegrationConnections connections;
    private final IntegrationHealthService health;
    private final MetaConnections meta;
    private final Onboarding onboarding;
    private final AuditTrail audit;
    private final Clock clock;

    DashboardController(SalesMetrics sales, DecisionInbox inbox, IntegrationConnections connections,
            IntegrationHealthService health, MetaConnections meta, Onboarding onboarding, AuditTrail audit,
            Clock clock) {
        this.sales = sales;
        this.inbox = inbox;
        this.connections = connections;
        this.health = health;
        this.meta = meta;
        this.onboarding = onboarding;
        this.audit = audit;
        this.clock = clock;
    }

    enum AttentionKind {
        DECISION_TO_APPROVE,
        INTEGRATION_UNHEALTHY,
        META_ERROR,
        ONBOARDING_INCOMPLETE
    }

    enum HealthSource {
        WOOCOMMERCE,
        META
    }

    @GetMapping
    DashboardResponse get(TrustedStoreScope scope) {
        Instant now = clock.instant();
        ZoneId zone = sales.zone(scope);
        LocalDate end = LocalDate.ofInstant(now, zone).minusDays(1);
        LocalWindow window = new LocalWindow(end.minusDays(DAYS - 1), end, zone);
        SalesSummary current = sales.sales(scope, window, now);
        SalesSummary base = sales.sales(scope, window.previous(), now);
        String currency = current.currency();
        List<String> orderRefs = List.of(AnalyticsController.ORDERS_SOURCE);
        List<MetricDto> metrics = List.of(
                MetricDto.of(current.netSales(), currency, orderRefs, base.netSales()),
                MetricDto.of(current.orders(), currency, orderRefs, base.orders()),
                MetricDto.of(sales.metaSpend(window, now), currency, List.of(META_SOURCE), null),
                MetricDto.of(sales.contributionMargin(window, now), currency, orderRefs, null));

        List<AttentionItem> attention = new ArrayList<>();
        Map<InboxGroup, Long> counts = inbox.counts(scope);
        for (DecisionInbox.Row row : inbox.page(scope, InboxGroup.TO_APPROVE, null, null, LIST_SIZE)) {
            DecisionProposal proposal = row.proposal().proposal();
            attention.add(new AttentionItem(AttentionKind.DECISION_TO_APPROVE, proposal.proposalId(),
                    proposal.recommendation().summary(), null, proposal.createdAt()));
        }

        List<HealthItem> healthItems = new ArrayList<>();
        for (IntegrationConnection connection : connections.list(scope)) {
            if (connection.provider() != IntegrationProvider.WOOCOMMERCE) {
                continue;
            }
            IntegrationHealth state = health.health(scope, connection);
            healthItems.add(new HealthItem(HealthSource.WOOCOMMERCE, connection.id(), state.status().name(),
                    state.reasons().stream().map(Enum::name).toList(), state.ordersCompleteUntil()));
            if (state.status() != IntegrationHealth.Status.HEALTHY) {
                attention.add(new AttentionItem(AttentionKind.INTEGRATION_UNHEALTHY, connection.id(), null,
                        state.status().name(), null));
            }
        }
        meta.find(scope).ifPresent(connection -> {
            healthItems.add(new HealthItem(HealthSource.META, connection.id(), connection.status().name(),
                    connection.lastErrorCode() == null ? List.of() : List.of(connection.lastErrorCode()),
                    connection.lastSyncAt()));
            if (connection.status() == MetaConnectionStatus.ERROR) {
                attention.add(metaAttention(connection));
            }
        });

        Onboarding.OnboardingState progress = onboarding.state(scope);
        // START_MONITORING is never complete in the pilot, so only the earlier steps count as unfinished setup.
        boolean setupIncomplete = progress.nextStep() != Onboarding.Step.START_MONITORING;
        if (setupIncomplete) {
            attention.add(new AttentionItem(AttentionKind.ONBOARDING_INCOMPLETE, null, null,
                    progress.nextStep().name(), null));
        }

        List<String> actions = ActivityController.TYPES.keySet().stream().sorted().toList();
        List<ActivityController.ActivitySummary> recent = audit.ofStore(scope, actions, null, null, LIST_SIZE)
                .stream().map(ActivityController::summary).toList();

        return new DashboardResponse(
                new DashboardMeta(window.from(), window.to(), zone.getId(), currency, now),
                metrics, current.series().stream().map(AnalyticsController.SeriesPoint::from).toList(),
                counts.get(InboxGroup.TO_APPROVE), List.copyOf(attention), recent, List.copyOf(healthItems),
                setupIncomplete ? progress.nextStep().name() : null);
    }

    private static AttentionItem metaAttention(MetaConnection connection) {
        return new AttentionItem(AttentionKind.META_ERROR, connection.id(), null, connection.lastErrorCode(),
                connection.lastCheckAt());
    }

    record DashboardMeta(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate from,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) LocalDate to,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String timezone,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant computedAt) {
    }

    record AttentionItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) AttentionKind kind,
            @Schema(description = "Proposal, connection or null for onboarding") UUID resourceId,
            @Schema(description = "Business summary of a decision") String summary,
            @Schema(description = "A status, error code or onboarding step") String code,
            Instant at) {
    }

    record HealthItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) HealthSource source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID connectionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> reasons,
            @Schema(description = "Orders complete until (WooCommerce) or the last ad sync (Meta)") Instant dataUntil) {
    }

    record DashboardResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DashboardMeta meta,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<MetricDto> metrics,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AnalyticsController.SeriesPoint> salesTrend,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long decisionsToApprove,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<AttentionItem> attentionItems,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
            List<ActivityController.ActivitySummary> recentActivities,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<HealthItem> integrationHealth,
            @Schema(description = "The next unfinished setup step; null when setup is done") String onboardingNextStep) {
    }
}
