package com.ecommerceagent.identity;

import java.util.function.Supplier;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Registers gauges read from the database when the registry publishes (ADR-0024). Each value is one count over all
 * stores from the reviewed definer function {@code operational_metric} (ADR-0022 point 4), the only way the runtime
 * role can count across stores; a failing query reports {@code NaN} instead of breaking the export.
 */
public final class DatabaseGauges {

    private static final Logger log = LoggerFactory.getLogger(DatabaseGauges.class);

    private final MeterRegistry registry;
    private final JdbcClient jdbc;

    public DatabaseGauges(MeterRegistry registry, JdbcClient jdbc) {
        this.registry = registry;
        this.jdbc = jdbc;
    }

    /** A gauge whose value is {@code operational_metric(name)}. */
    public DatabaseGauges gauge(String name, String description, String baseUnit) {
        return gauge(name, description, baseUnit, null);
    }

    /** A gauge whose value is {@code operational_metric(name, maxAttempts)}. */
    public DatabaseGauges gauge(String name, String description, String baseUnit, Integer maxAttempts) {
        Supplier<Number> value = () -> {
            try {
                Number number = jdbc.sql("SELECT operational_metric(:name, :maxAttempts)")
                        .param("name", name).param("maxAttempts", maxAttempts, java.sql.Types.INTEGER)
                        .query(Double.class).optional().orElse(null);
                return number == null ? 0 : number;
            } catch (RuntimeException e) {
                log.warn("Gauge {} could not be read: {}", name, e.getMessage());
                return Double.NaN;
            }
        };
        Gauge.builder(name, value).description(description).baseUnit(baseUnit).strongReference(true)
                .register(registry);
        return this;
    }
}
