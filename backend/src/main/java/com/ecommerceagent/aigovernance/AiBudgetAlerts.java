package com.ecommerceagent.aigovernance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.PlatformOperator;
import com.ecommerceagent.identity.PlatformOperators;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Budget alerts (ADR-0027 section 14): every budget of the current periods, at every level, is compared with the
 * thresholds of the parameter set; a threshold reached for the first time in a period is recorded once in
 * {@code ai_budget_alert} (repeated and parallel evaluations record nothing twice), counted in
 * {@code operator_ai_budget_alerts_total} and published as {@link AiBudgetThresholdReached}. The hard limit itself
 * is enforced at reservation, fail closed, whatever the alerts say. Platform operators read the alerts.
 */
@Service
public class AiBudgetAlerts {

    private static final Logger log = LoggerFactory.getLogger(AiBudgetAlerts.class);

    private final JdbcClient jdbc;
    private final AiBudgetAlertSettings settings;
    private final PlatformOperators operators;
    private final MeterRegistry meters;
    private final ApplicationEventPublisher events;

    AiBudgetAlerts(JdbcClient jdbc, AiBudgetAlertSettings settings, PlatformOperators operators, MeterRegistry meters,
            ApplicationEventPublisher events) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.operators = operators;
        this.meters = meters;
        this.events = events;
    }

    /** Records the thresholds reached since the last evaluation and returns them. */
    public List<AiBudgetAlert> evaluate() {
        List<AiBudgetAlert> recorded = jdbc.sql("SELECT * FROM ai_record_budget_alerts(?::integer[], ?)")
                .params(settings.thresholdPercents().toArray(Integer[]::new), settings.version())
                .query(AiBudgetAlerts::alert)
                .list();
        for (AiBudgetAlert alert : recorded) {
            meters.counter("operator.ai.budget.alerts", "level", alert.level(), "period", alert.periodType(),
                    "threshold", Integer.toString(alert.thresholdPercent())).increment();
            log.warn("AI budget {} {} reached {}% ({} of {} {})", alert.level(), alert.periodType(),
                    alert.thresholdPercent(), alert.usedAmount().toPlainString(),
                    alert.amountLimit().toPlainString(), alert.currency());
            events.publishEvent(new AiBudgetThresholdReached(alert));
        }
        return recorded;
    }

    /** The recorded alerts, newest first. */
    public List<AiBudgetAlert> recent(PlatformOperator operator, Instant since, int limit) {
        operators.require(operator.userId());
        return jdbc.sql("SELECT * FROM ai_budget_alerts(?, ?)")
                .params(since == null ? null : Timestamp.from(since), limit)
                .query(AiBudgetAlerts::alert)
                .list();
    }

    private static AiBudgetAlert alert(ResultSet rs, int row) throws SQLException {
        return new AiBudgetAlert(rs.getObject("id", UUID.class), rs.getString("level"), rs.getString("scope_key"),
                rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class),
                rs.getString("period_type"), rs.getTimestamp("period_start").toInstant(),
                rs.getTimestamp("period_end").toInstant(), rs.getInt("threshold_percent"),
                rs.getBigDecimal("amount_limit"), rs.getBigDecimal("used_amount"), rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant());
    }
}
