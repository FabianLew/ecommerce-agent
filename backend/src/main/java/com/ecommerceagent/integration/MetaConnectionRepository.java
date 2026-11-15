package com.ecommerceagent.integration;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Meta connections and their sealed token (ADR-0017). Every method filters by the trusted scope, except
 * {@link #due(Instant, int)}, which only lists ids for the poller; the poller then builds a system scope per store.
 */
@Repository
class MetaConnectionRepository {

    private static final String COLUMNS = """
            id, account_id, status, token_hint, account_name, currency, can_write, last_check_at, last_success_at,
            last_error_code, last_sync_at, last_sync_error, ads_seen, next_sync_after
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    MetaConnectionRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Inserts a connection; returns empty if the store already has one. */
    Optional<MetaConnection> create(TrustedStoreScope scope, String accountId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(clock.instant());
        int inserted = jdbc.sql("""
                        INSERT INTO meta_connection (id, organization_id, store_id, account_id, status, created_at,
                                                     updated_at)
                        VALUES (:id, :organizationId, :storeId, :accountId, 'PENDING_CREDENTIALS', :now, :now)
                        ON CONFLICT (store_id) DO NOTHING
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("accountId", accountId)
                .param("now", now)
                .update();
        return inserted == 1 ? find(scope) : Optional.empty();
    }

    Optional<MetaConnection> find(TrustedStoreScope scope) {
        return jdbc.sql("SELECT " + COLUMNS + """
                        FROM meta_connection WHERE organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(MetaConnectionRepository::map)
                .optional();
    }

    void saveToken(TrustedStoreScope scope, UUID connectionId, String name, SecretCipher.Sealed sealed,
            String tokenHint) {
        Timestamp now = Timestamp.from(clock.instant());
        int written = jdbc.sql("""
                        INSERT INTO meta_credential (connection_id, organization_id, store_id, name, ciphertext, nonce,
                                                     key_id, updated_at)
                        SELECT c.id, c.organization_id, c.store_id, :name, :ciphertext, :nonce, :keyId, :now
                        FROM meta_connection c
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
                .param("now", now)
                .update();
        if (written != 1) {
            throw new IntegrationNotFoundException(connectionId);
        }
        // A new token needs a new check: the previous result and write permission no longer apply.
        jdbc.sql("""
                        UPDATE meta_connection
                        SET status = 'CHECK_REQUIRED', token_hint = :hint, can_write = false, last_error_code = NULL,
                            next_sync_after = NULL, updated_at = :now
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("hint", tokenHint)
                .param("now", now)
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    Optional<SecretCipher.Sealed> credential(TrustedStoreScope scope, UUID connectionId, String name) {
        return jdbc.sql("""
                        SELECT ciphertext, nonce, key_id FROM meta_credential
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

    void recordCheck(TrustedStoreScope scope, UUID connectionId, MetaConnectionStatus status, String errorCode,
            String accountName, String currency, boolean canWrite, Instant checkedAt) {
        boolean success = status == MetaConnectionStatus.CONNECTED;
        jdbc.sql("""
                        UPDATE meta_connection
                        SET status = :status, last_check_at = :checkedAt,
                            last_success_at = CASE WHEN :success THEN :checkedAt ELSE last_success_at END,
                            last_error_code = :error,
                            account_name = COALESCE(:accountName, account_name),
                            currency = COALESCE(:currency, currency),
                            can_write = CASE WHEN :success THEN :canWrite ELSE false END,
                            updated_at = :checkedAt
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("status", status.name())
                .param("checkedAt", Timestamp.from(checkedAt))
                .param("success", success)
                .param("error", errorCode)
                .param("accountName", accountName)
                .param("currency", currency)
                .param("canWrite", canWrite)
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    void recordSync(TrustedStoreScope scope, UUID connectionId, Instant at, int adsSeen, String error,
            Instant nextSyncAfter) {
        jdbc.sql("""
                        UPDATE meta_connection
                        SET last_sync_at = CASE WHEN :ok THEN :at ELSE last_sync_at END,
                            ads_seen = CASE WHEN :ok THEN :adsSeen ELSE ads_seen END,
                            last_sync_error = :error, next_sync_after = :next, updated_at = :at
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("ok", error == null)
                .param("at", Timestamp.from(at))
                .param("adsSeen", adsSeen)
                .param("error", error, java.sql.Types.VARCHAR)
                .param("next", nextSyncAfter == null ? null : Timestamp.from(nextSyncAfter), java.sql.Types.TIMESTAMP)
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** A failed token on a running connection: polling stops until the operator checks a new token. */
    void markAuthFailed(TrustedStoreScope scope, UUID connectionId, String errorCode, Instant at) {
        jdbc.sql("""
                        UPDATE meta_connection
                        SET status = 'ERROR', last_error_code = :error, can_write = false, updated_at = :at
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("error", errorCode)
                .param("at", Timestamp.from(at))
                .param("id", connectionId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
    }

    /** Connected stores whose next poll is due; ids only, for the poller. */
    List<DueConnection> due(Instant now, int limit) {
        return jdbc.sql("""
                        SELECT id, organization_id, store_id FROM meta_connection
                        WHERE status = 'CONNECTED' AND (next_sync_after IS NULL OR next_sync_after <= :now)
                        ORDER BY last_sync_at NULLS FIRST
                        LIMIT :limit
                        """)
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query((rs, n) -> new DueConnection(rs.getObject("id", UUID.class),
                        rs.getObject("organization_id", UUID.class), rs.getObject("store_id", UUID.class)))
                .list();
    }

    record DueConnection(UUID id, UUID organizationId, UUID storeId) {
    }

    private static MetaConnection map(ResultSet rs, int row) throws SQLException {
        return new MetaConnection(rs.getObject("id", UUID.class), rs.getString("account_id"),
                MetaConnectionStatus.valueOf(rs.getString("status")), rs.getString("token_hint"),
                rs.getString("account_name"), rs.getString("currency"), rs.getBoolean("can_write"),
                instant(rs, "last_check_at"), instant(rs, "last_success_at"), rs.getString("last_error_code"),
                instant(rs, "last_sync_at"), rs.getString("last_sync_error"), rs.getInt("ads_seen"),
                instant(rs, "next_sync_after"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
