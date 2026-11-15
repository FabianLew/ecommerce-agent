package com.ecommerceagent.intelligence.model;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;

import com.ecommerceagent.aigovernance.AiBudgetGovernor;
import com.ecommerceagent.aigovernance.AiCallEstimate;
import com.ecommerceagent.aigovernance.AiUsageLedger;
import com.ecommerceagent.aigovernance.ProviderUsage;
import com.ecommerceagent.aigovernance.ReservationDecision;
import com.ecommerceagent.aigovernance.ReservationRequest;
import com.ecommerceagent.aigovernance.SendDecision;
import com.ecommerceagent.aigovernance.AiAdmission;
import com.ecommerceagent.aigovernance.Settlement;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.model.provider.ProviderGateways;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The only production path to a paid model (ADR-0027 section 10). For one call it:
 * <ol>
 *   <li>refuses what cannot be sent (deadline passed, provider not configured) before reserving anything;</li>
 *   <li>reserves the conservative maximum cost against every budget of the store ({@link AiBudgetGovernor});</li>
 *   <li>marks the reservation sent, committed, and sends only when this caller won ({@link SendDecision#SEND});</li>
 *   <li>settles the provider's reported usage, or keeps the reservation {@code UNKNOWN} when the call may have been
 *       charged and its result is not known; an error response the provider does not bill is settled at zero.</li>
 * </ol>
 * A failure of governance itself sends nothing (fail closed). It never runs inside a database transaction, so the
 * "sent" mark is committed before the request leaves; a worker that dies after it leaves the reservation to the sweep.
 */
@Component
public class GovernedModelGateway {

    private static final Logger log = LoggerFactory.getLogger(GovernedModelGateway.class);

    private final ProviderGateways providers;
    private final AiBudgetGovernor governor;
    private final AiUsageLedger ledger;
    private final AiAdmission admission;
    private final Clock clock;

    public GovernedModelGateway(ProviderGateways providers, AiBudgetGovernor governor, AiUsageLedger ledger,
            AiAdmission admission, Clock clock) {
        this.providers = providers;
        this.governor = governor;
        this.ledger = ledger;
        this.admission = admission;
        this.clock = clock;
    }

    /**
     * Admits an agent run before its first model call (ADR-0027 section 6.2): the run holds a lease until
     * {@link #endRun} or until {@code ttl} passes.
     *
     * @throws AiCallDenied when the run is not admitted ({@code AI_STOPPED}, {@code STORE_LIMIT},
     *     {@code GLOBAL_LIMIT}, {@code LEASE_ENDED}); nothing was reserved or sent
     */
    public void startRun(TrustedStoreScope scope, java.util.UUID runId, String automationType, int maxStoreRuns,
            java.time.Duration ttl) {
        AiAdmission.Admission admitted = admission.admit(scope, runId, automationType, maxStoreRuns, ttl);
        if (!admitted.granted()) {
            throw new AiCallDenied(admitted.name());
        }
    }

    /** Ends the run's lease; safe to call for a run that was never admitted. */
    public void endRun(TrustedStoreScope scope, java.util.UUID runId) {
        admission.release(scope, runId);
    }

    public ModelCapabilities capabilities(ModelId model) {
        return providers.forModel(model).capabilities(model);
    }

    /**
     * @throws AiCallDenied when governance does not admit the call; nothing was sent
     * @throws ModelFailure when nothing was sent because the call could not be attempted
     * @throws GovernedModelFailure when the provider failed after the call was admitted
     */
    public GovernedTurn complete(TrustedStoreScope scope, GovernedCall call) {
        Objects.requireNonNull(scope, "scope");
        ModelRequest request = call.request();
        ModelId model = request.model();
        ModelGateway provider = providers.forModel(model);
        if (!clock.instant().isBefore(request.deadline())) {
            throw new ModelFailure(ModelFailure.Type.TIMEOUT, "Deadline passed before the call", null, null, true,
                    null);
        }
        if (!provider.available(model)) {
            throw new ModelFailure(ModelFailure.Type.UNAVAILABLE, "The provider is not configured", null, null, true,
                    null);
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            // The sent mark must be committed before the request leaves; inside a transaction it would not be.
            throw new AiCallDenied("CALL_INSIDE_TRANSACTION");
        }

        AiCallEstimate estimate = AiCallEstimate.of(model.provider(), model.name(), providers.region(model),
                call.estimatedInputTokens(), request.maxOutputTokens());
        ReservationDecision decision = governor.reserve(scope, new ReservationRequest(call.idempotencyKey(),
                call.automationType(), request.runId(), estimate));
        if (decision instanceof ReservationDecision.Denied denied) {
            throw new AiCallDenied(denied.reason());
        }
        ReservationDecision.Reserved reserved = (ReservationDecision.Reserved) decision;
        SendDecision send = ledger.markSent(scope, reserved.reservationId());
        if (send == SendDecision.STOPPED) {
            throw new AiCallDenied(AiCallDenied.AI_STOPPED);
        }
        if (send != SendDecision.SEND) {
            throw new AiCallDenied("NOT_SENDABLE:" + send);
        }

        ModelTurn turn;
        try {
            turn = provider.complete(request);
        } catch (ModelFailure failure) {
            if (failure.confirmedNotCharged()) {
                ledger.settle(scope, reserved.reservationId(),
                        new ProviderUsage(failure.providerRequestId(), 0, 0, 0, 0, Map.of()));
                throw new GovernedModelFailure(failure, false, BigDecimal.ZERO);
            }
            ledger.markUnknown(scope, reserved.reservationId(), "MODEL_FAILURE:" + failure.type());
            throw new GovernedModelFailure(failure, true, reserved.amount());
        } catch (RuntimeException e) {
            ledger.markUnknown(scope, reserved.reservationId(), "PROVIDER_ERROR:" + e.getClass().getSimpleName());
            throw new GovernedModelFailure(new ModelFailure(ModelFailure.Type.UNKNOWN, e.getClass().getSimpleName(),
                    null, null, e), true, reserved.amount());
        }

        ModelUsage usage = turn.usage();
        if (!usage.known()) {
            ledger.markUnknown(scope, reserved.reservationId(), "USAGE_NOT_REPORTED");
            return new GovernedTurn(turn, reserved.reservationId(), reserved.amount(), reserved.currency(), false);
        }
        try {
            Settlement settlement = ledger.settle(scope, reserved.reservationId(),
                    ProviderUsage.tokens(turn.providerRequestId(), usage.inputTokens(), usage.outputTokens()));
            if (settlement.overran()) {
                log.warn("AI call {} cost {} {} over its reservation", reserved.reservationId(), settlement.overrun(),
                        reserved.currency());
            }
            return new GovernedTurn(turn, reserved.reservationId(), settlement.cost(), reserved.currency(), true);
        } catch (RuntimeException e) {
            // The answer is real; its cost stays reserved and the sweep turns the reservation UNKNOWN.
            log.warn("AI call {} could not be settled: {}", reserved.reservationId(), e.toString());
            return new GovernedTurn(turn, reserved.reservationId(), reserved.amount(), reserved.currency(), false);
        }
    }
}
