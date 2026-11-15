package com.ecommerceagent.aigovernance;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * The budget governor on PostgreSQL. The price and the conservative cost are computed here; the reservation itself
 * is one call of the reviewed function {@code ai_reserve}, which locks every applicable balance in a fixed order,
 * checks all limits and records the reservation and its charges in one statement.
 */
@Service
class PostgresAiBudgetGovernor implements AiBudgetGovernor {

    private final JdbcClient jdbc;
    private final AiPricingCatalog catalog;
    private final AiGovernanceSettings settings;
    private final Clock clock;

    PostgresAiBudgetGovernor(JdbcClient jdbc, AiPricingCatalog catalog, AiGovernanceSettings settings, Clock clock) {
        this.jdbc = jdbc;
        this.catalog = catalog;
        this.settings = settings;
        this.clock = clock;
    }

    @Override
    public CostQuote quote(AiCallEstimate estimate) {
        return switch (price(estimate, clock.instant())) {
            case Priced p -> new CostQuote.Priced(p.cost.amount(), p.price.currency(), p.price.id(),
                    p.price.priceVersion());
            case Unpriced u -> new CostQuote.Unpriced(u.reason);
        };
    }

    private sealed interface Pricing {
    }

    private record Priced(AiPrice price, CostCalculator.MaxCost cost) implements Pricing {
    }

    private record Unpriced(String reason) implements Pricing {
    }

    /** The current, verified price and the call's conservative maximum cost; shared by quotes and reservations. */
    private Pricing price(AiCallEstimate estimate, Instant now) {
        Optional<AiPrice> found = catalog.current(estimate.provider(), estimate.model(), estimate.region(), now);
        if (found.isEmpty()) {
            return new Unpriced("NOT_PRICED");
        }
        AiPrice price = found.get();
        if (price.verifiedAt() == null) {
            return new Unpriced("PRICE_UNVERIFIED");
        }
        if (price.verifiedAt().isBefore(now.minus(settings.priceMaxVerificationAge()))) {
            return new Unpriced("PRICE_STALE");
        }
        Optional<String> unbounded = CostCalculator.unboundedReason(price, estimate);
        if (unbounded.isPresent()) {
            return new Unpriced(unbounded.get());
        }
        CostCalculator.MaxCost cost = CostCalculator.maxCost(price, estimate, settings.inputTokenMargin());
        if (cost.maxInputTokens() > Integer.MAX_VALUE || cost.maxOutputTokens() > Integer.MAX_VALUE) {
            return new Unpriced("TOKEN_LIMIT_TOO_LARGE");
        }
        return new Priced(price, cost);
    }

    @Override
    public ReservationDecision reserve(TrustedStoreScope scope, ReservationRequest request) {
        requireBound(scope);
        Instant now = clock.instant();
        AiCallEstimate estimate = request.estimate();
        Priced priced;
        switch (price(estimate, now)) {
            case Priced p -> priced = p;
            case Unpriced u -> {
                return new ReservationDecision.Denied(u.reason);
            }
        }
        AiPrice price = priced.price;
        CostCalculator.MaxCost cost = priced.cost;
        Instant expiresAt = now.plus(settings.reservationTimeout());
        return jdbc.sql("""
                        SELECT reservation_id, outcome, reason, state, amount
                        FROM ai_reserve(?, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?)
                        """)
                .params(request.idempotencyKey(), request.automationType(), request.agentRunId(), price.id(),
                        (int) cost.maxInputTokens(), (int) cost.maxOutputTokens(), cost.amount(), price.currency(),
                        Timestamp.from(expiresAt))
                .query((rs, n) -> switch (rs.getString("outcome")) {
                    case "RESERVED", "DUPLICATE" -> new ReservationDecision.Reserved(
                            rs.getObject("reservation_id", UUID.class), rs.getBigDecimal("amount"), price.currency(),
                            price.id(), price.priceVersion(), cost.maxInputTokens(), cost.maxOutputTokens(),
                            "DUPLICATE".equals(rs.getString("outcome")), rs.getString("state"));
                    case "DENIED" -> new ReservationDecision.Denied(rs.getString("reason"));
                    default -> throw new AiGovernanceException("Unknown reservation outcome " + rs.getString("outcome"));
                })
                .single();
    }

    @Override
    public void release(TrustedStoreScope scope, UUID reservationId, String reason) {
        requireBound(scope);
        jdbc.sql("SELECT ai_release(?, ?)").params(reservationId, reason).query(String.class).single();
    }

    static void requireBound(TrustedStoreScope scope) {
        TrustedStoreScope bound = StoreRowGuard.current()
                .orElseThrow(() -> new AiGovernanceException("AI_SCOPE_NOT_BOUND"));
        if (!bound.storeId().equals(scope.storeId()) || !bound.organizationId().equals(scope.organizationId())) {
            throw new AiGovernanceException("AI_SCOPE_MISMATCH");
        }
    }
}
