package com.ecommerceagent.decisions;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.metrics.CanonicalJson;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proposals of an episode (ADR-0012). The backend wraps a proposer's recommendation in the v1 envelope, validates
 * the whole document and stores it as the next revision, superseding the previous one; an invalid document is
 * kept only as a rejected attempt.
 */
@Service
public class DecisionProposals {

    static final String RESOURCE = "decision_proposal";

    private final ProposalValidator validator;
    private final JdbcClient jdbc;
    private final AuditTrail audit;
    private final Clock clock;

    DecisionProposals(ProposalValidator validator, JdbcClient jdbc, AuditTrail audit, Clock clock) {
        this.validator = validator;
        this.jdbc = jdbc;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Submits a recommendation for an episode. {@code recommendation} is the proposer's raw JSON (for an agent:
     * exactly what the model returned), so it is validated as received.
     */
    @Transactional
    public ProposalSubmission submit(TrustedStoreScope scope, UUID opportunityId, UUID contextSnapshotId,
            Proposer proposer, UUID agentRunId, JsonNode recommendation, Duration ttl) {
        // Seconds precision keeps the envelope identical after a round trip through the database.
        Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        int revision = nextRevision(scope, opportunityId);
        ObjectNode document = ProposalJson.envelope(DecisionProposal.SCHEMA_VERSION, UUID.randomUUID(), revision,
                new OpportunitySubject(opportunityId), proposer, agentRunId, contextSnapshotId, now, now.plus(ttl));
        document.set("recommendation", recommendation);

        ProposalValidation validation = validator.validate(scope, document);
        if (!validation.valid()) {
            UUID rejection = reject(scope, opportunityId, contextSnapshotId, proposer, agentRunId, document,
                    validation.errors(), now);
            audit.record(AuditEntry.inScope(scope, "PROPOSAL_INVALID", "proposal_rejection", rejection.toString(),
                    Map.of("opportunityId", opportunityId.toString(), "proposer", proposer.name(), "codes",
                            String.join(",", validation.errors().stream().map(ProposalError::code).distinct()
                                    .toList()))));
            return new ProposalSubmission(null, rejection, validation.errors());
        }
        DecisionProposal proposal = validation.proposal();
        jdbc.sql("""
                        UPDATE decision_proposal SET status = 'SUPERSEDED', status_changed_at = :now
                        WHERE opportunity_id = :opportunityId AND status = 'PROPOSED'
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("now", Timestamp.from(now))
                .param("opportunityId", opportunityId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update();
        JsonNode content = ProposalJson.write(proposal);
        jdbc.sql("""
                        INSERT INTO decision_proposal (id, organization_id, store_id, opportunity_id, revision,
                                                       schema_version, context_snapshot_id, proposer_kind,
                                                       proposer_name, proposer_version, agent_run_id, disposition,
                                                       content, payload_hash, status, created_at, expires_at,
                                                       status_changed_at)
                        VALUES (:id, :organizationId, :storeId, :opportunityId, :revision, :schemaVersion,
                                :snapshotId, :proposerKind, :proposerName, :proposerVersion, :agentRunId,
                                :disposition, CAST(:content AS jsonb), :hash, 'PROPOSED', :createdAt, :expiresAt,
                                :createdAt)
                        """)
                .param("id", proposal.proposalId())
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("opportunityId", opportunityId)
                .param("revision", proposal.revision())
                .param("schemaVersion", proposal.schemaVersion())
                .param("snapshotId", proposal.contextSnapshotId())
                .param("proposerKind", proposer.kind().name())
                .param("proposerName", proposer.name())
                .param("proposerVersion", proposer.version())
                .param("agentRunId", agentRunId)
                .param("disposition", proposal.recommendation().disposition().name())
                .param("content", CanonicalJson.write(content))
                .param("hash", CanonicalJson.hash(content))
                .param("createdAt", Timestamp.from(proposal.createdAt()))
                .param("expiresAt", Timestamp.from(proposal.expiresAt()))
                .update();
        audit.record(AuditEntry.inScope(scope, "PROPOSAL_CREATED", RESOURCE, proposal.proposalId().toString(),
                Map.of("opportunityId", opportunityId.toString(), "revision", Integer.toString(proposal.revision()),
                        "disposition", proposal.recommendation().disposition().name(), "proposer", proposer.name())));
        return new ProposalSubmission(find(scope, proposal.proposalId()).orElseThrow(), null, List.of());
    }

    public Optional<StoredProposal> find(TrustedStoreScope scope, UUID proposalId) {
        return jdbc.sql(SELECT + " AND id = :id")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("id", proposalId)
                .query(DecisionProposals::map)
                .optional();
    }

    /** All revisions of an episode, oldest first. */
    public List<StoredProposal> ofOpportunity(TrustedStoreScope scope, UUID opportunityId) {
        return jdbc.sql(SELECT + " AND opportunity_id = :opportunityId ORDER BY revision")
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("opportunityId", opportunityId)
                .query(DecisionProposals::map)
                .list();
    }

    /** Moves a proposal out of {@code PROPOSED}; returns false when it already left it. */
    @Transactional
    public boolean close(TrustedStoreScope scope, UUID proposalId, ProposalStatus next) {
        if (next == ProposalStatus.PROPOSED) {
            throw new IllegalArgumentException("A proposal cannot return to PROPOSED");
        }
        boolean closed = jdbc.sql("""
                        UPDATE decision_proposal SET status = :next, status_changed_at = :now
                        WHERE id = :id AND status = 'PROPOSED'
                          AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("next", next.name())
                .param("now", Timestamp.from(clock.instant()))
                .param("id", proposalId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .update() == 1;
        if (closed) {
            audit.record(AuditEntry.inScope(scope, "PROPOSAL_STATUS_CHANGED", RESOURCE, proposalId.toString(),
                    Map.of("from", ProposalStatus.PROPOSED.name(), "to", next.name())));
        }
        return closed;
    }

    /** Error codes of the rejected attempts of an episode, oldest first (audit, tests). */
    public List<List<String>> rejections(TrustedStoreScope scope, UUID opportunityId) {
        return jdbc.sql("""
                        SELECT error_codes FROM proposal_rejection
                        WHERE opportunity_id = :opportunityId AND organization_id = :organizationId
                          AND store_id = :storeId
                        ORDER BY created_at, id
                        """)
                .param("opportunityId", opportunityId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query((rs, n) -> List.of((String[]) rs.getArray("error_codes").getArray()))
                .list();
    }

    private int nextRevision(TrustedStoreScope scope, UUID opportunityId) {
        return jdbc.sql("""
                        SELECT coalesce(max(revision), 0) + 1 FROM decision_proposal
                        WHERE opportunity_id = :opportunityId AND organization_id = :organizationId
                          AND store_id = :storeId
                        """)
                .param("opportunityId", opportunityId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single();
    }

    private UUID reject(TrustedStoreScope scope, UUID opportunityId, UUID contextSnapshotId, Proposer proposer,
            UUID agentRunId, JsonNode document, List<ProposalError> errors, Instant now) {
        boolean knownOpportunity = jdbc.sql("""
                        SELECT count(*) FROM opportunity
                        WHERE id = :id AND organization_id = :organizationId AND store_id = :storeId
                        """)
                .param("id", opportunityId)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .query(Integer.class)
                .single() == 1;
        ArrayNode errorNodes = JsonNodeFactory.instance.arrayNode();
        errors.forEach(error -> errorNodes.addObject().put("code", error.code()).put("path", error.path())
                .put("message", error.message()));
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO proposal_rejection (id, organization_id, store_id, opportunity_id,
                                                        context_snapshot_id, proposer_kind, proposer_name,
                                                        proposer_version, agent_run_id, payload, payload_hash,
                                                        error_codes, errors, created_at)
                        VALUES (:id, :organizationId, :storeId, :opportunityId, :snapshotId, :proposerKind,
                                :proposerName, :proposerVersion, :agentRunId, CAST(:payload AS jsonb), :hash,
                                CAST(:codes AS text[]), CAST(:errors AS jsonb), :now)
                        """)
                .param("id", id)
                .param("organizationId", scope.organizationId())
                .param("storeId", scope.storeId())
                .param("opportunityId", knownOpportunity ? opportunityId : null)
                .param("snapshotId", contextSnapshotId)
                .param("proposerKind", proposer.kind().name())
                .param("proposerName", proposer.name())
                .param("proposerVersion", proposer.version())
                .param("agentRunId", agentRunId)
                .param("payload", document.toString())
                .param("hash", hash(document))
                .param("codes", "{" + String.join(",", errors.stream().map(ProposalError::code).distinct().toList())
                        + "}")
                .param("errors", errorNodes.toString())
                .param("now", Timestamp.from(now))
                .update();
        return id;
    }

    /** Canonical hash when the document allows it; otherwise the hash of its JSON text (e.g. decimal numbers). */
    private static String hash(JsonNode document) {
        try {
            return CanonicalJson.hash(document);
        } catch (IllegalArgumentException e) {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(document.toString().getBytes(StandardCharsets.UTF_8));
                return "sha256:" + HexFormat.of().formatHex(digest);
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
    }

    private static final String SELECT = """
            SELECT id, content, payload_hash, status, status_changed_at
            FROM decision_proposal
            WHERE organization_id = :organizationId AND store_id = :storeId""";

    private static StoredProposal map(ResultSet rs, int row) throws SQLException {
        return new StoredProposal(ProposalJson.read(CanonicalJson.read(rs.getString("content"))),
                rs.getString("payload_hash"), ProposalStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("status_changed_at").toInstant());
    }
}
