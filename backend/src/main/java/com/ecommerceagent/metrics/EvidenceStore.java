package com.ecommerceagent.metrics;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Durable, immutable evidence (ADR-0011). Written only when a decision context is built; identical content in
 * a store resolves to the same evidence. Times are kept to the second, as in the canonical record.
 */
@Service
public class EvidenceStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    EvidenceStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** The record the hash is computed from: everything except the id, computation and storage times. */
    static ObjectNode canonicalRecord(TrustedStoreScope scope, EvidenceDraft draft) {
        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("storeId", scope.storeId().toString());
        record.put("kind", draft.type().kind().name());
        record.put("type", draft.type().name());
        record.put("resourceRef", draft.resourceRef());
        record.put("definitionVersion", draft.definitionVersion());
        record.set("content", draft.content());
        var inputs = record.putArray("inputEvidenceIds");
        draft.inputEvidenceIds().forEach(id -> inputs.add(id.toString()));
        record.put("observedAt", seconds(draft.observedAt()).toString());
        record.put("availableAt", seconds(draft.availableAt()).toString());
        return record;
    }

    @Transactional
    public Evidence record(TrustedStoreScope scope, EvidenceDraft draft) {
        String hash = CanonicalJson.hash(canonicalRecord(scope, draft));
        jdbc.sql("""
                        INSERT INTO evidence (id, organization_id, store_id, kind, evidence_type, resource_ref,
                                              definition_version, content, content_hash, input_evidence_ids,
                                              observed_at, available_at, computed_at, created_at)
                        VALUES (:id, :organizationId, :storeId, :kind, :type, :resourceRef, :definitionVersion,
                                CAST(:content AS jsonb), :hash, CAST(:inputs AS uuid[]), :observedAt, :availableAt,
                                :computedAt, :now)
                        ON CONFLICT (store_id, content_hash) DO NOTHING
                        """)
                .param("id", UUID.randomUUID())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("kind", draft.type().kind().name())
                .param("type", draft.type().name())
                .param("resourceRef", draft.resourceRef())
                .param("definitionVersion", draft.definitionVersion())
                .param("content", CanonicalJson.write(draft.content()))
                .param("hash", hash)
                .param("inputs", "{" + String.join(",", draft.inputEvidenceIds().stream().map(UUID::toString)
                        .toList()) + "}")
                .param("observedAt", Timestamp.from(seconds(draft.observedAt())))
                .param("availableAt", Timestamp.from(seconds(draft.availableAt())))
                .param("computedAt", Timestamp.from(seconds(draft.computedAt())))
                .param("now", Timestamp.from(clock.instant()))
                .update();
        return byHash(scope, hash).orElseThrow();
    }

    public Optional<Evidence> find(TrustedStoreScope scope, UUID evidenceId) {
        return jdbc.sql(SELECT + " AND id = :id")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("id", evidenceId)
                .query(EvidenceStore::map)
                .optional();
    }

    /** The evidence of this store among the given ids; ids of other stores or unknown ids are left out. */
    public List<Evidence> findAll(TrustedStoreScope scope, Collection<UUID> evidenceIds) {
        if (evidenceIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT + " AND id IN (:ids) ORDER BY observed_at, id")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("ids", List.copyOf(evidenceIds))
                .query(EvidenceStore::map)
                .list();
    }

    /** Recomputes the hash from the stored content; false means the record no longer matches its hash. */
    public boolean verify(TrustedStoreScope scope, Evidence evidence) {
        EvidenceDraft draft = new EvidenceDraft(evidence.type(), evidence.resourceRef(), evidence.definitionVersion(),
                evidence.content(), evidence.inputEvidenceIds(), evidence.observedAt(), evidence.availableAt(),
                evidence.computedAt());
        return CanonicalJson.hash(canonicalRecord(scope, draft)).equals(evidence.contentHash());
    }

    private Optional<Evidence> byHash(TrustedStoreScope scope, String hash) {
        return jdbc.sql(SELECT + " AND content_hash = :hash")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("hash", hash)
                .query(EvidenceStore::map)
                .optional();
    }

    private static final String SELECT = """
            SELECT id, version, evidence_type, resource_ref, definition_version, content::text AS content,
                   content_hash, input_evidence_ids, observed_at, available_at, computed_at
            FROM evidence
            WHERE organization_id = :organizationId AND store_id = :storeId""";

    private static Evidence map(ResultSet rs, int row) throws SQLException {
        Array inputs = rs.getArray("input_evidence_ids");
        List<UUID> inputIds = inputs == null ? List.of()
                : Arrays.stream((Object[]) inputs.getArray()).map(id -> (UUID) id).toList();
        JsonNode content = CanonicalJson.read(rs.getString("content"));
        return new Evidence(rs.getObject("id", UUID.class), rs.getInt("version"),
                EvidenceType.valueOf(rs.getString("evidence_type")), rs.getString("resource_ref"),
                rs.getString("definition_version"), content, rs.getString("content_hash"), inputIds,
                rs.getTimestamp("observed_at").toInstant(), rs.getTimestamp("available_at").toInstant(),
                rs.getTimestamp("computed_at").toInstant());
    }

    private static Instant seconds(Instant instant) {
        return instant.truncatedTo(ChronoUnit.SECONDS);
    }
}
