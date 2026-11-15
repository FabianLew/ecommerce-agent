package com.ecommerceagent.aigovernance;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Reconciles {@code UNKNOWN} reservations with their provider (ADR-0027 section 4.5): a reservation that kept the
 * provider's request id is looked up by that id with the provider's {@link ProviderUsageSource}. A confirmed charge
 * is settled from the provider's usage at the reservation's own price, a confirmed non-charge returns the
 * reservation, and anything else stays {@code UNKNOWN} for a platform operator or the conservative settlement. One
 * run has a time limit and a batch size; what is left is taken by the next run. Providers without a source are
 * skipped, so without any source this does nothing.
 */
@Service
public class AiUnknownReconciler {

    static final String SERVICE = "ai-reconciliation";

    private static final Logger log = LoggerFactory.getLogger(AiUnknownReconciler.class);

    private final AiReconciliation reconciliation;
    private final AiUsageLedger ledger;
    private final StoreAccess storeAccess;
    private final Map<String, ProviderUsageSource> sources;
    private final AiGovernanceSettings settings;
    private final MeterRegistry meters;
    private final Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    AiUnknownReconciler(AiReconciliation reconciliation, AiUsageLedger ledger, StoreAccess storeAccess,
            ObjectProvider<ProviderUsageSource> sources, AiGovernanceSettings settings, MeterRegistry meters,
            Clock clock) {
        this(reconciliation, ledger, storeAccess, sources.orderedStream().toList(), settings, meters, clock);
    }

    AiUnknownReconciler(AiReconciliation reconciliation, AiUsageLedger ledger, StoreAccess storeAccess,
            java.util.List<ProviderUsageSource> sources, AiGovernanceSettings settings, MeterRegistry meters,
            Clock clock) {
        this.reconciliation = reconciliation;
        this.ledger = ledger;
        this.storeAccess = storeAccess;
        this.sources = sources.stream()
                .collect(Collectors.toUnmodifiableMap(ProviderUsageSource::provider, Function.identity()));
        this.settings = settings;
        this.meters = meters;
        this.clock = clock;
    }

    /** One run: how many reservations were closed and how many stay unknown. */
    public RunResult run() {
        if (sources.isEmpty()) {
            return new RunResult(0, 0, 0, false);
        }
        Instant deadline = clock.instant().plus(settings.reconciliationTimeLimit());
        int charged = 0;
        int notCharged = 0;
        int unconfirmed = 0;
        boolean cutShort = false;
        for (UncertainReservation reservation : reconciliation.uncertain(settings.reconciliationBatchSize())) {
            if (!reservation.unknown() || reservation.providerRequestId() == null) {
                continue;
            }
            ProviderUsageSource source = sources.get(reservation.provider());
            if (source == null) {
                continue;
            }
            if (!clock.instant().isBefore(deadline)) {
                cutShort = true;
                break;
            }
            String result = reconcileOne(source, reservation);
            meters.counter("operator.ai.reconciliation", "provider", reservation.provider(), "result", result)
                    .increment();
            switch (result) {
                case "CHARGED" -> charged++;
                case "NOT_CHARGED" -> notCharged++;
                default -> unconfirmed++;
            }
        }
        if (charged + notCharged > 0 || cutShort) {
            log.info("AI reconciliation: {} charged, {} not charged, {} still unknown{}", charged, notCharged,
                    unconfirmed, cutShort ? ", cut short by the time limit" : "");
        }
        return new RunResult(charged, notCharged, unconfirmed, cutShort);
    }

    private String reconcileOne(ProviderUsageSource source, UncertainReservation reservation) {
        try {
            ProviderUsageSource.Lookup lookup = source.lookup(reservation.model(), reservation.providerRequestId());
            TrustedStoreScope scope = storeAccess.systemScope(SERVICE, reservation.organizationId(),
                    reservation.storeId()).orElse(null);
            if (scope == null) {
                return "UNCONFIRMED";
            }
            return switch (lookup) {
                case ProviderUsageSource.Lookup.Charged charged -> {
                    if (!reservation.providerRequestId().equals(charged.usage().providerCallId())) {
                        // The answer is about another request: never matched by anything but the id.
                        yield "UNCONFIRMED";
                    }
                    ledger.settle(scope, reservation.reservationId(), charged.usage());
                    yield "CHARGED";
                }
                case ProviderUsageSource.Lookup.NotCharged notCharged -> {
                    ledger.reconcile(scope, reservation.reservationId(), ReconciliationOutcome.NOT_CHARGED, null,
                            "Provider confirmed no charge: " + notCharged.evidence());
                    yield "NOT_CHARGED";
                }
                case ProviderUsageSource.Lookup.Unconfirmed unconfirmed -> "UNCONFIRMED";
            };
        } catch (RuntimeException e) {
            log.warn("AI reconciliation of reservation {} failed: {}", reservation.reservationId(), e.toString());
            return "FAILED";
        }
    }

    /** Counts of one run. */
    public record RunResult(int charged, int notCharged, int unconfirmed, boolean cutShort) {
    }
}
