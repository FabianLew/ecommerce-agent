package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

import com.ecommerceagent.identity.Actor;
import com.ecommerceagent.identity.StoreRowGuard;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/** The usage ledger on PostgreSQL: each step is one call of a reviewed function on the bound store's connection. */
@Service
class PostgresAiUsageLedger implements AiUsageLedger {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    PostgresAiUsageLedger(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SendDecision markSent(TrustedStoreScope scope, UUID reservationId) {
        requireBound(scope);
        String answer = jdbc.sql("SELECT ai_mark_sent(?::uuid)").param(reservationId).query(String.class).single();
        return SendDecision.valueOf(answer);
    }

    @Override
    public Settlement settle(TrustedStoreScope scope, UUID reservationId, ProviderUsage usage) {
        requireBound(scope);
        Objects.requireNonNull(usage, "usage");
        return jdbc.sql("""
                        SELECT outcome, cost, overrun
                        FROM ai_settle(?::uuid, ?, ?, ?, ?, ?, ?::jsonb)
                        """)
                .params(reservationId, usage.providerCallId(), usage.inputTokens(), usage.outputTokens(),
                        usage.cacheWriteTokens(), usage.cacheReadTokens(), JSON.writeValueAsString(usage.extraUnits()))
                .query((rs, n) -> settlement(rs.getString("outcome"), rs.getBigDecimal("cost"),
                        rs.getBigDecimal("overrun"), "SETTLED"))
                .single();
    }

    @Override
    public void markUnknown(TrustedStoreScope scope, UUID reservationId, String reason) {
        requireBound(scope);
        jdbc.sql("SELECT ai_mark_unknown(?::uuid, ?)").params(reservationId, reason).query(String.class).single();
    }

    @Override
    public Settlement reconcile(TrustedStoreScope scope, UUID reservationId, ReconciliationOutcome outcome,
            BigDecimal cost, String reason) {
        requireBound(scope);
        Objects.requireNonNull(outcome, "outcome");
        return jdbc.sql("""
                        SELECT outcome, cost, overrun
                        FROM ai_reconcile(?::uuid, ?, ?, ?, ?)
                        """)
                .params(reservationId, outcome.name(), cost, recordedBy(scope.actor()), reason)
                .query((rs, n) -> settlement(rs.getString("outcome"), rs.getBigDecimal("cost"),
                        rs.getBigDecimal("overrun"), "RECONCILED_" + outcome.name()))
                .single();
    }

    private static Settlement settlement(String outcome, BigDecimal cost, BigDecimal overrun, String first) {
        boolean repeated = "DUPLICATE".equals(outcome);
        return new Settlement(repeated ? first : outcome, cost, overrun, repeated);
    }

    private static String recordedBy(Actor actor) {
        return switch (actor) {
            case Actor.UserActor user -> "USER:" + user.userId();
            case Actor.SystemActor system -> "SYSTEM:" + system.service();
        };
    }

    private static void requireBound(TrustedStoreScope scope) {
        PostgresAiBudgetGovernor.requireBound(scope);
    }
}
