package com.ecommerceagent.decisions;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import com.ecommerceagent.metrics.Evidence;
import com.ecommerceagent.metrics.EvidenceStore;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/** Writes each context snapshot once and reads it only within its store (ADR-0011). */
@Service
public class ContextSnapshots {

    private final JdbcClient jdbc;
    private final EvidenceStore evidence;
    private final Clock clock;

    ContextSnapshots(JdbcClient jdbc, EvidenceStore evidence, Clock clock) {
        this.jdbc = jdbc;
        this.evidence = evidence;
        this.clock = clock;
    }

    /**
     * Stores a snapshot. Every evidence id must belong to the store; the hash covers the schema, the moment,
     * the content and the evidence set.
     */
    @Transactional
    public ContextSnapshot create(TrustedStoreScope scope, UUID opportunityId, String contentSchema, Instant asOf,
            JsonNode content, Collection<UUID> evidenceIds) {
        List<UUID> ids = List.copyOf(new LinkedHashSet<>(evidenceIds));
        List<Evidence> found = evidence.findAll(scope, ids);
        if (found.size() != ids.size()) {
            throw new IllegalArgumentException("Snapshot refers to evidence outside the store");
        }
        Instant moment = asOf.truncatedTo(ChronoUnit.SECONDS);
        String hash = CanonicalJson.hash(hashRecord(scope, contentSchema, moment, content, ids));
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                        INSERT INTO context_snapshot (id, organization_id, store_id, opportunity_id, content_schema,
                                                      as_of, content, content_hash, created_at)
                        VALUES (:id, :organizationId, :storeId, :opportunityId, :schema, :asOf,
                                CAST(:content AS jsonb), :hash, :now)
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("opportunityId", opportunityId)
                .param("schema", contentSchema)
                .param("asOf", Timestamp.from(moment))
                .param("content", CanonicalJson.write(content))
                .param("hash", hash)
                .param("now", Timestamp.from(now))
                .update();
        for (UUID evidenceId : ids) {
            jdbc.sql("""
                            INSERT INTO context_snapshot_evidence (snapshot_id, evidence_id, organization_id, store_id)
                            VALUES (:snapshotId, :evidenceId, :organizationId, :storeId)
                            """)
                    .param("snapshotId", id)
                    .param("evidenceId", evidenceId)
                    .param("organizationId", scope.organizationId())
                    .param("storeId", scope.storeId())
                    .update();
        }
        return find(scope, id).orElseThrow();
    }

    public Optional<ContextSnapshot> find(TrustedStoreScope scope, UUID snapshotId) {
        Optional<ContextSnapshot> head = jdbc.sql("""
                        SELECT id, opportunity_id, content_schema, as_of, content::text AS content, content_hash,
                               created_at
                        FROM context_snapshot
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", snapshotId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> new ContextSnapshot(rs.getObject("id", UUID.class),
                        rs.getObject("opportunity_id", UUID.class), rs.getString("content_schema"),
                        rs.getTimestamp("as_of").toInstant(), CanonicalJson.read(rs.getString("content")),
                        rs.getString("content_hash"), List.of(), rs.getTimestamp("created_at").toInstant()))
                .optional();
        return head.map(snapshot -> new ContextSnapshot(snapshot.id(), snapshot.opportunityId(),
                snapshot.contentSchema(), snapshot.asOf(), snapshot.content(), snapshot.contentHash(),
                evidenceIds(scope, snapshot.id()), snapshot.createdAt()));
    }

    /** Recomputes the hash from the stored snapshot; false means it no longer matches. */
    public boolean verify(TrustedStoreScope scope, ContextSnapshot snapshot) {
        return CanonicalJson.hash(hashRecord(scope, snapshot.contentSchema(), snapshot.asOf(), snapshot.content(),
                snapshot.evidenceIds())).equals(snapshot.contentHash());
    }

    private List<UUID> evidenceIds(TrustedStoreScope scope, UUID snapshotId) {
        return jdbc.sql("""
                        SELECT evidence_id FROM context_snapshot_evidence
                        WHERE snapshot_id = :snapshotId AND organization_id = :organizationId AND store_id = :storeId
                        ORDER BY evidence_id
                        """)
                .param("snapshotId", snapshotId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(UUID.class)
                .list();
    }

    private static ObjectNode hashRecord(TrustedStoreScope scope, String schema, Instant asOf, JsonNode content,
            Collection<UUID> evidenceIds) {
        ObjectNode record = JsonNodeFactory.instance.objectNode();
        record.put("storeId", scope.storeId().toString());
        record.put("contentSchema", schema);
        record.put("asOf", asOf.truncatedTo(ChronoUnit.SECONDS).toString());
        record.set("content", content);
        var ids = record.putArray("evidenceIds");
        evidenceIds.stream().map(UUID::toString).sorted().forEach(ids::add);
        return record;
    }
}
