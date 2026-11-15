package com.ecommerceagent.web;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.aigovernance.Adjustment;
import com.ecommerceagent.aigovernance.AiReconciliation;
import com.ecommerceagent.aigovernance.AiReconciliationException;
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
 * Platform operations on AI spending (ADR-0027 sections 4.5 and 15), for platform operators only: the calls whose
 * cost is not confirmed, their reconciliation and the correction of a conservative one. Store members get 403 like
 * anyone else who is not listed in {@code platform_operator}.
 */
@RestController
@RequestMapping("/api/v1/platform/ai/reservations")
class PlatformAiController {

    private final PlatformOperators operators;
    private final AiReconciliation reconciliation;

    PlatformAiController(PlatformOperators operators, AiReconciliation reconciliation) {
        this.operators = operators;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/uncertain")
    ListResponse<UncertainReservationDto> uncertainReservations(RegisteredUser user,
            @RequestParam(name = "limit", defaultValue = "100") int limit) {
        if (limit < 1 || limit > 500) {
            throw new InvalidQueryException("INVALID_LIMIT");
        }
        PlatformOperator operator = operators.require(user.id());
        return ListResponse.complete(reconciliation.uncertain(operator, limit).stream()
                .map(UncertainReservationDto::from).toList());
    }

    @PostMapping("/{reservationId}/reconciliation")
    ReconciliationResponse reconcileReservation(RegisteredUser user, @PathVariable UUID reservationId,
            @RequestBody ReconciliationRequest body) {
        PlatformOperator operator = operators.require(user.id());
        Settlement settlement = reconciliation.reconcile(operator, reservationId, outcome(body.outcome()),
                body.cost(), body.reason());
        return new ReconciliationResponse(settlement.outcome(), settlement.cost(), null, settlement.repeated());
    }

    @PostMapping("/{reservationId}/correction")
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
