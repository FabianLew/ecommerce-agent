package com.ecommerceagent.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.aigovernance.Adjustment;
import com.ecommerceagent.aigovernance.AiBudgetAlert;
import com.ecommerceagent.aigovernance.AiBudgetAlerts;
import com.ecommerceagent.aigovernance.AiReconciliation;
import com.ecommerceagent.aigovernance.AiReconciliationException;
import com.ecommerceagent.aigovernance.AiStopChange;
import com.ecommerceagent.aigovernance.AiStops;
import com.ecommerceagent.aigovernance.ReconciliationOutcome;
import com.ecommerceagent.aigovernance.Settlement;
import com.ecommerceagent.aigovernance.UncertainReservation;
import com.ecommerceagent.identity.PlatformOperator;
import com.ecommerceagent.identity.PlatformOperators;
import com.ecommerceagent.identity.RegisteredUser;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Platform operations on AI spending (ADR-0027 sections 4.5, 12 and 15), for platform operators only: the calls whose
 * cost is not confirmed, their reconciliation and the correction of a conservative one, and the scoped AI stops. Store members get 403 like
 * anyone else who is not listed in {@code platform_operator}.
 */
@RestController
@RequestMapping("/api/v1/platform/ai")
class PlatformAiController {

    private final PlatformOperators operators;
    private final AiReconciliation reconciliation;
    private final AiStops stops;
    private final AiBudgetAlerts budgetAlerts;

    PlatformAiController(PlatformOperators operators, AiReconciliation reconciliation, AiStops stops,
            AiBudgetAlerts budgetAlerts) {
        this.operators = operators;
        this.reconciliation = reconciliation;
        this.stops = stops;
        this.budgetAlerts = budgetAlerts;
    }

    /** Budgets that reached an alert threshold, newest first; amounts in the budget's currency. */
    @GetMapping("/budget-alerts")
    ListResponse<BudgetAlertDto> budgetAlerts(RegisteredUser user,
            @RequestParam(name = "since", required = false) Instant since,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 1000) {
            throw new InvalidQueryException("INVALID_LIMIT");
        }
        PlatformOperator operator = operators.require(user.id());
        return ListResponse.complete(budgetAlerts.recent(operator, since, limit).stream()
                .map(BudgetAlertDto::from).toList());
    }

    /** The scoped AI stops in force now (the platform stop is shown by the {@code operator_ai_stopped} gauge). */
    @GetMapping("/stops")
    ListResponse<AiStopDto> activeAiStops(RegisteredUser user) {
        PlatformOperator operator = operators.require(user.id());
        return ListResponse.complete(stops.active(operator).stream().map(AiStopDto::from).toList());
    }

    /** Every stop and resume decision, newest first. */
    @GetMapping("/stops/history")
    ListResponse<AiStopDto> aiStopHistory(RegisteredUser user,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 1000) {
            throw new InvalidQueryException("INVALID_LIMIT");
        }
        PlatformOperator operator = operators.require(user.id());
        return ListResponse.complete(stops.history(operator, limit).stream().map(AiStopDto::from).toList());
    }

    /** Stops or resumes AI for a store, an automation on every store, or an automation on one store. */
    @PostMapping("/stops")
    AiStopChangeResponse changeAiStop(RegisteredUser user, @RequestBody AiStopRequest body) {
        PlatformOperator operator = operators.require(user.id());
        boolean changed = stops.change(operator, body.storeId(), body.automationType(), body.stopped(),
                body.reason());
        return new AiStopChangeResponse(changed);
    }

    @GetMapping("/reservations/uncertain")
    ListResponse<UncertainReservationDto> uncertainReservations(RegisteredUser user,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) {
            throw new InvalidQueryException("INVALID_LIMIT");
        }
        PlatformOperator operator = operators.require(user.id());
        return ListResponse.complete(reconciliation.uncertain(operator, limit).stream()
                .map(UncertainReservationDto::from).toList());
    }

    @PostMapping("/reservations/{reservationId}/reconciliation")
    ReconciliationResponse reconcileReservation(RegisteredUser user, @PathVariable UUID reservationId,
            @RequestBody ReconciliationRequest body) {
        PlatformOperator operator = operators.require(user.id());
        Settlement settlement = reconciliation.reconcile(operator, reservationId, outcome(body.outcome()),
                body.cost(), body.reason());
        return new ReconciliationResponse(settlement.outcome(), settlement.cost(), null, settlement.repeated());
    }

    @PostMapping("/reservations/{reservationId}/correction")
    ReconciliationResponse correctReservation(RegisteredUser user, @PathVariable UUID reservationId,
            @RequestBody ReconciliationRequest body) {
        PlatformOperator operator = operators.require(user.id());
        Adjustment adjustment = reconciliation.correct(operator, reservationId, outcome(body.outcome()), body.cost(),
                body.reason());
        return new ReconciliationResponse(adjustment.outcome(), adjustment.cost(), adjustment.delta(),
                adjustment.repeated());
    }

    private static ReconciliationOutcome outcome(String value) {
        try {
            return ReconciliationOutcome.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            throw new AiReconciliationException(AiReconciliationException.Kind.INVALID, "UNKNOWN_OUTCOME", e);
        }
    }

    record BudgetAlertDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "PLATFORM, ORGANIZATION, STORE or AUTOMATION") String level,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String scopeKey,
            UUID organizationId,
            UUID storeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "MONTHLY or DAILY") String periodType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant periodStart,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant periodEnd,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int thresholdPercent,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Decimal string") String amountLimit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Reserved plus spent when the threshold was reached; decimal string")
            String usedAmount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "ISO 4217 code") String currency,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {

        static BudgetAlertDto from(AiBudgetAlert a) {
            return new BudgetAlertDto(a.id(), a.level(), a.scopeKey(), a.organizationId(), a.storeId(),
                    a.periodType(), a.periodStart(), a.periodEnd(), a.thresholdPercent(),
                    a.amountLimit().toPlainString(), a.usedAmount().toPlainString(), a.currency(), a.createdAt());
        }
    }

    record AiStopRequest(
            @Schema(description = "The store; null for an automation on every store") UUID storeId,
            @Schema(description = "The automation; null for every automation of the store") String automationType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "true stops AI, false resumes it")
            boolean stopped,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "Why, 1 to 500 characters")
            String reason) {
    }

    record AiStopChangeResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "false when the target was already in that state; nothing was recorded")
            boolean changed) {
    }

    record AiStopDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "STORE, AUTOMATION or STORE_AUTOMATION") String level,
            UUID organizationId,
            UUID storeId,
            String automationType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stopped,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String changedBy,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant changedAt) {

        static AiStopDto from(AiStopChange c) {
            return new AiStopDto(c.id(), c.level(), c.organizationId(), c.storeId(), c.automationType(), c.stopped(),
                    c.reason(), c.changedBy(), c.changedAt());
        }
    }

    record ReconciliationRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "NOT_CHARGED, CHARGED or CONSERVATIVE (CONSERVATIVE only for an UNKNOWN call)")
            String outcome,
            @Schema(description = "The confirmed amount in the reservation's currency; required for CHARGED only")
            BigDecimal cost,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "What the provider shows, 1 to 500 characters") String reason) {
    }

    record ReconciliationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String outcome,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal cost,
            @Schema(description = "Change of every charged budget, for a correction") BigDecimal delta,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The same decision was already recorded; nothing changed") boolean repeated) {
    }

    record UncertainReservationDto(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID reservationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID organizationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID storeId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String automationType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String model,
            String providerRequestId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "UNKNOWN or RECONCILED_CONSERVATIVE") String state,
            String stateReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal reservedAmount,
            BigDecimal recordedCost,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String currency,
            Instant sentAt,
            Instant unknownAt,
            Instant settledAt) {

        static UncertainReservationDto from(UncertainReservation r) {
            return new UncertainReservationDto(r.reservationId(), r.organizationId(), r.storeId(), r.automationType(),
                    r.provider(), r.model(), r.providerRequestId(), r.state(), r.stateReason(), r.reservedAmount(),
                    r.recordedCost(), r.currency(), r.sentAt(), r.unknownAt(), r.settledAt());
        }
    }
}
