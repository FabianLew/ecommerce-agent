package com.ecommerceagent.businessprofile;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.businessprofile.BusinessProfile.PlannedAdSpend;
import com.ecommerceagent.businessprofile.ProfileEnums.Goal;
import com.ecommerceagent.businessprofile.ProfileEnums.Priority;
import com.ecommerceagent.businessprofile.ProfileEnums.RiskPreference;
import com.ecommerceagent.businessprofile.ProfileEnums.Status;
import com.ecommerceagent.businessprofile.ProfileEnums.TaxBasis;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Profile versions, filtered by the trusted scope (ADR-0005, ADR-0018). Approved rows are never updated. */
@Repository
class BusinessProfileRepository {

    private static final String COLUMNS = """
            id, version, status, goal, planning_horizon_days, risk_preference, tax_basis, priorities,
            planned_ad_spend_minor, planned_ad_spend_currency, planned_ad_spend_period, note, revision, approved_by,
            approved_at, effective_from, updated_at
            """;

    private final JdbcClient jdbc;

    BusinessProfileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BusinessProfile> withStatus(TrustedStoreScope scope, Status status) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM store_business_profile
                        WHERE status = :status AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("status", status.name())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(BusinessProfileRepository::map)
                .optional();
    }

    Optional<BusinessProfile> lockDraft(TrustedStoreScope scope) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM store_business_profile
                        WHERE status = 'DRAFT' AND organization_id = :organizationId AND store_id = :storeId
                        FOR UPDATE
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(BusinessProfileRepository::map)
                .optional();
    }

    List<BusinessProfile> history(TrustedStoreScope scope) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM store_business_profile
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        ORDER BY version DESC
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(BusinessProfileRepository::map)
                .list();
    }

    int nextVersion(TrustedStoreScope scope) {
        return jdbc.sql("""
                        SELECT COALESCE(max(version), 0) + 1 FROM store_business_profile
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single();
    }

    void insertDraft(TrustedStoreScope scope, UUID id, int version, ProfileDraftInput input, String currency,
            UUID createdBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO store_business_profile (id, organization_id, store_id, version, status, goal,
                            planning_horizon_days, risk_preference, tax_basis, priorities, planned_ad_spend_minor,
                            planned_ad_spend_currency, planned_ad_spend_period, note, revision, created_by,
                            created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :version, 'DRAFT', :goal, :horizon, :risk, :taxBasis,
                            CAST(:priorities AS text[]), :spend, :currency, :period, :note, 1, :createdBy, :now, :now)
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("version", version)
                .params(fields(input, currency))
                .param("createdBy", createdBy, java.sql.Types.OTHER)
                .param("now", Timestamp.from(now))
                .update();
    }

    void updateDraft(TrustedStoreScope scope, UUID id, ProfileDraftInput input, String currency, Instant now) {
        jdbc.sql("""
                        UPDATE store_business_profile
                        SET goal = :goal, planning_horizon_days = :horizon, risk_preference = :risk,
                            tax_basis = :taxBasis, priorities = CAST(:priorities AS text[]),
                            planned_ad_spend_minor = :spend, planned_ad_spend_currency = :currency,
                            planned_ad_spend_period = :period, note = :note, revision = revision + 1,
                            updated_at = :now
                        WHERE id = :id AND status = 'DRAFT' AND organization_id = :organizationId
                          AND store_id = :storeId
                        """)
                .params(fields(input, currency))
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    void supersedeApproved(TrustedStoreScope scope, Instant now) {
        jdbc.sql("""
                        UPDATE store_business_profile SET status = 'SUPERSEDED', updated_at = :now
                        WHERE status = 'APPROVED' AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("now", Timestamp.from(now))
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    void approve(TrustedStoreScope scope, UUID id, UUID approvedBy, Instant now) {
        jdbc.sql("""
                        UPDATE store_business_profile
                        SET status = 'APPROVED', approved_by = :approvedBy, approved_at = :now, effective_from = :now,
                            updated_at = :now
                        WHERE id = :id AND status = 'DRAFT' AND organization_id = :organizationId
                          AND store_id = :storeId
                        """)
                .param("approvedBy", approvedBy)
                .param("now", Timestamp.from(now))
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    String storeCurrency(TrustedStoreScope scope) {
        return jdbc.sql("SELECT currency FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query(String.class)
                .single();
    }

    private static java.util.Map<String, Object> fields(ProfileDraftInput input, String currency) {
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put("goal", input.goal() == null ? null : input.goal().name());
        values.put("horizon", input.planningHorizonDays());
        values.put("risk", input.riskPreference() == null ? null : input.riskPreference().name());
        values.put("taxBasis", (input.taxBasis() == null ? TaxBasis.EX_TAX : input.taxBasis()).name());
        values.put("priorities", "{" + String.join(",", (input.priorities() == null ? List.<Priority>of()
                : input.priorities()).stream().map(Enum::name).distinct().toList()) + "}");
        values.put("spend", input.plannedAdSpendMinor());
        values.put("currency", input.plannedAdSpendMinor() == null ? null : currency);
        values.put("period", input.plannedAdSpendMinor() == null ? null : input.plannedAdSpendPeriod());
        values.put("note", input.note());
        return values;
    }

    private static BusinessProfile map(ResultSet rs, int row) throws SQLException {
        long spend = rs.getLong("planned_ad_spend_minor");
        PlannedAdSpend planned = rs.wasNull() ? null : new PlannedAdSpend(spend,
                rs.getString("planned_ad_spend_currency"), rs.getString("planned_ad_spend_period"));
        int horizon = rs.getInt("planning_horizon_days");
        Integer planningHorizon = rs.wasNull() ? null : horizon;
        Array priorities = rs.getArray("priorities");
        List<Priority> parsed = Arrays.stream((String[]) priorities.getArray()).map(Priority::valueOf).toList();
        return new BusinessProfile(rs.getObject("id", UUID.class), rs.getInt("version"),
                Status.valueOf(rs.getString("status")), enumOrNull(Goal.class, rs.getString("goal")), planningHorizon,
                enumOrNull(RiskPreference.class, rs.getString("risk_preference")),
                TaxBasis.valueOf(rs.getString("tax_basis")), parsed, planned, rs.getString("note"),
                rs.getInt("revision"), rs.getObject("approved_by", UUID.class), instant(rs, "approved_at"),
                instant(rs, "effective_from"), instant(rs, "updated_at"));
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
