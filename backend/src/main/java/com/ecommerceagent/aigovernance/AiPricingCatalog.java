package com.ecommerceagent.aigovernance;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The versioned price catalog (ADR-0027 section 9). The price in effect for a model and region is the newest row
 * whose {@code effective_from} has passed; older rows stay for the history of reserved and settled costs.
 */
@Repository
class AiPricingCatalog {

    private static final TypeReference<Map<String, BigDecimal>> EXTRAS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    AiPricingCatalog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<AiPrice> current(String provider, String model, String region, Instant at) {
        return jdbc.sql("""
                        SELECT * FROM ai_price
                        WHERE provider = :provider AND model = :model AND region = :region
                          AND effective_from <= :at
                        ORDER BY effective_from DESC
                        LIMIT 1
                        """)
                .param("provider", provider)
                .param("model", model)
                .param("region", region)
                .param("at", Timestamp.from(at))
                .query(this::price)
                .optional();
    }

    private AiPrice price(ResultSet rs, int n) throws SQLException {
        Map<String, BigDecimal> extras;
        try {
            extras = new LinkedHashMap<>(JSON.readValue(rs.getString("extras"), EXTRAS));
        } catch (Exception e) {
            throw new SQLException("Unreadable extras of price " + rs.getString("id"), e);
        }
        Timestamp verified = rs.getTimestamp("verified_at");
        return new AiPrice(rs.getObject("id", UUID.class), rs.getString("provider"), rs.getString("model"),
                rs.getString("region"), rs.getString("price_version"), rs.getString("currency"),
                rs.getBigDecimal("input_per_mtok"), rs.getBigDecimal("output_per_mtok"),
                rs.getBigDecimal("cache_write_per_mtok"), rs.getBigDecimal("cache_read_per_mtok"), extras,
                rs.getTimestamp("effective_from").toInstant(), rs.getString("source"),
                verified == null ? null : verified.toInstant());
    }
}
