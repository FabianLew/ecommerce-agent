package com.ecommerceagent.integration;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Connections and their sealed credentials (ADR-0005: explicit SQL filtered by the trusted scope).
 * Connection queries never select from {@code integration_credential}.
 */
@Repository
class IntegrationConnectionRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COLUMNS = """
            id, provider, store_url, status, key_hint, last_check_at, last_success_at, last_error_code,
            last_check_warnings::text AS warnings, source_currency
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    IntegrationConnectionRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Inserts a connection; returns empty if the store already has one for the provider. */
    Optional<IntegrationConnection> create(TrustedStoreScope scope, IntegrationProvider provider, URI storeUrl) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.sql("""
                        INSERT INTO integration_connection (id, organization_id, store_id, provider, store_url, status,
                                                            created_at, updated_at)
                        VALUES (:id, :organizationId, :storeId, :provider, :storeUrl, 'PENDING_CREDENTIALS', :now, :now)
                        ON CONFLICT (store_id, provider) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("provider", provider.name())
                .param("storeUrl", storeUrl.toString())
                .param("now", now)
                .update();
        return inserted == 1 ? find(scope, id) : Optional.empty();
    }

    List<IntegrationConnection> list(TrustedStoreScope scope) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM integration_connection
                        WHERE organization_id = :organizationId AND store_id = :storeId
                        ORDER BY provider
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(IntegrationConnectionRepository::map)
                .list();
    }

    Optional<IntegrationConnection> find(TrustedStoreScope scope, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM integration_connection
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(IntegrationConnectionRepository::map)
                .optional();
    }

    Optional<IntegrationConnection> findByProvider(TrustedStoreScope scope, IntegrationProvider provider) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM integration_connection
                        WHERE provider = :provider AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("provider", provider.name())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(IntegrationConnectionRepository::map)
                .optional();
    }

    /** Store currency from the store settings, to compare with the source. */
    String storeCurrency(TrustedStoreScope scope) {
        return jdbc.sql("SELECT currency FROM store WHERE id = :storeId AND organization_id = :organizationId")
                .param("storeId", scope.storeId())
                .param("organizationId", scope.organizationId())
                .query(String.class)
                .single();
    }

    void saveCredential(TrustedStoreScope scope, UUID connectionId, String name, SecretCipher.Sealed sealed) {
        int written = jdbc.sql("""
                        INSERT INTO integration_credential (connection_id, organization_id, store_id, name, ciphertext,
                                                            nonce, key_id, updated_at)
                        SELECT c.id, c.organization_id, c.store_id, :name, :ciphertext, :nonce, :keyId, :now
                        FROM integration_connection c
                        WHERE c.id = :connectionId AND c.organization_id = :organizationId AND c.store_id = :storeId
                        ON CONFLICT (connection_id, name) DO UPDATE
                            SET ciphertext = EXCLUDED.ciphertext, nonce = EXCLUDED.nonce, key_id = EXCLUDED.key_id,
                                updated_at = EXCLUDED.updated_at
                        """)
                .param("connectionId", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("name", name)
                .param("ciphertext", sealed.ciphertext())
                .param("nonce", sealed.nonce())
                .param("keyId", sealed.keyId())
                .param("now", Timestamp.from(clock.instant()))
                .update();
        if (written != 1) {
            throw new IntegrationNotFoundException(connectionId);
        }
    }

    Optional<SecretCipher.Sealed> credential(TrustedStoreScope scope, UUID connectionId, String name) {
        return jdbc.sql("""
                        SELECT ciphertext, nonce, key_id FROM integration_credential
                        WHERE connection_id = :connectionId AND name = :name
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("connectionId", connectionId)
                .param("name", name)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new SecretCipher.Sealed(rs.getBytes("ciphertext"), rs.getBytes("nonce"),
                        rs.getString("key_id")))
                .optional();
    }

    /** New credentials or address: the previous check result no longer applies. */
    void requireCheck(TrustedStoreScope scope, UUID connectionId, String keyHint) {
        jdbc.sql("""
                        UPDATE integration_connection
                        SET status = 'CHECK_REQUIRED', key_hint = :keyHint, last_error_code = NULL,
                            last_check_warnings = '[]'::jsonb, updated_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("keyHint", keyHint)
                .param("now", Timestamp.from(clock.instant()))
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    void recordCheck(TrustedStoreScope scope, UUID connectionId, ConnectionCheck check) {
        Timestamp checkedAt = Timestamp.from(check.checkedAt());
        jdbc.sql("""
                        UPDATE integration_connection
                        SET status = :status, last_check_at = :checkedAt,
                            last_success_at = CASE WHEN :success THEN :checkedAt ELSE last_success_at END,
                            last_error_code = :error, last_check_warnings = CAST(:warnings AS jsonb),
                            source_currency = COALESCE(:currency, source_currency), updated_at = :checkedAt
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("status", check.status().name())
                .param("checkedAt", checkedAt)
                .param("success", check.status() == ConnectionStatus.CONNECTED)
                .param("error", check.error() == null ? null : check.error().name())
                .param("warnings", JSON.writeValueAsString(check.warnings().stream().map(Enum::name).toList()))
                .param("currency", check.sourceCurrency())
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    private static IntegrationConnection map(ResultSet rs, int rowNum) throws SQLException {
        String error = rs.getString("last_error_code");
        String[] warnings = JSON.readValue(rs.getString("warnings"), String[].class);
        return new IntegrationConnection(
                rs.getObject("id", UUID.class),
                IntegrationProvider.valueOf(rs.getString("provider")),
                URI.create(rs.getString("store_url")),
                ConnectionStatus.valueOf(rs.getString("status")),
                rs.getString("key_hint"),
                instant(rs.getTimestamp("last_check_at")),
                instant(rs.getTimestamp("last_success_at")),
                error == null ? null : ConnectionCheckError.valueOf(error),
                Arrays.stream(warnings).map(ConnectionWarning::valueOf).toList(),
                rs.getString("source_currency"));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
