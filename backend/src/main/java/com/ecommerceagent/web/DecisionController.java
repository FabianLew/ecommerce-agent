package com.ecommerceagent.web;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.automation.guardian.ApprovalException;
import com.ecommerceagent.automation.execution.ActionExecution;
import com.ecommerceagent.automation.execution.ActionOutcomes;
import com.ecommerceagent.automation.execution.AuthorizedAction;
import com.ecommerceagent.automation.execution.ExecutionAuthority;
import com.ecommerceagent.automation.execution.ExecutionLedger;
import com.ecommerceagent.automation.guardian.DecisionApprovals;
import com.ecommerceagent.automation.guardian.PolicyCheck;
import com.ecommerceagent.automation.guardian.PolicyEvaluation;
import com.ecommerceagent.automation.guardian.PolicyEvaluations;
import com.ecommerceagent.commerce.CatalogRepository;
import com.ecommerceagent.commerce.Variant;
import com.ecommerceagent.decisions.ContextSnapshot;
import com.ecommerceagent.decisions.ContextSnapshots;
import com.ecommerceagent.decisions.DecisionDisposition;
import com.ecommerceagent.decisions.DecisionInbox;
import com.ecommerceagent.decisions.DecisionProposal;
import com.ecommerceagent.decisions.DecisionProposals;
import com.ecommerceagent.decisions.EvidenceRef;
import com.ecommerceagent.decisions.InboxGroup;
import com.ecommerceagent.decisions.Opportunities;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.OpportunityStatus;
import com.ecommerceagent.decisions.OpportunitySubject;
import com.ecommerceagent.decisions.PauseAd;
import com.ecommerceagent.decisions.ProposalStatus;
import com.ecommerceagent.decisions.ProposerKind;
import com.ecommerceagent.decisions.StoredProposal;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.intelligence.inventory.InventoryContext;
import com.ecommerceagent.metrics.Evidence;
import com.ecommerceagent.metrics.EvidenceKind;
import com.ecommerceagent.metrics.EvidenceStore;
import com.ecommerceagent.metrics.EvidenceType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

/**
 * Decision Inbox and Decision Detail (04 §5, ADR-0014), the approval and rejection commands (ADR-0020 point 2) and
 * what followed a decision (ADR-0021). In shadow mode {@code allowedActions} stays empty.
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/decisions")
class DecisionController {

    static final String SHADOW_MODE = "SHADOW_MODE";
    static final String PROPOSAL_EXPIRED = "PROPOSAL_EXPIRED";

    private final DecisionInbox inbox;
    private final DecisionProposals proposals;
    private final Opportunities opportunities;
    private final ContextSnapshots snapshots;
    private final EvidenceStore evidence;
    private final PolicyEvaluations policies;
    private final CatalogRepository catalog;
    private final StoreAccess storeAccess;
    private final DecisionApprovals approvals;
    private final ExecutionAuthority authority;
    private final ExecutionLedger ledger;
    private final ActionOutcomes outcomes;
    private final Clock clock;

    DecisionController(DecisionInbox inbox, DecisionProposals proposals, Opportunities opportunities,
            ContextSnapshots snapshots, EvidenceStore evidence, PolicyEvaluations policies, CatalogRepository catalog,
            StoreAccess storeAccess, DecisionApprovals approvals, ExecutionAuthority authority, ExecutionLedger ledger,
            ActionOutcomes outcomes, Clock clock) {
        this.inbox = inbox;
        this.proposals = proposals;
        this.opportunities = opportunities;
        this.snapshots = snapshots;
        this.evidence = evidence;
        this.policies = policies;
        this.catalog = catalog;
        this.storeAccess = storeAccess;
        this.approvals = approvals;
        this.authority = authority;
        this.ledger = ledger;
        this.outcomes = outcomes;
        this.clock = clock;
    }

    @GetMapping
    DecisionListResponse list(TrustedStoreScope scope, HttpServletRequest request,
            @RequestParam(required = false) String group, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        InboxGroup selected = group(group);
        int size = PageCursor.limit(limit);
        String list = "decisions:" + selected.name();
        PageCursor position = PageCursor.decode(cursor, list);
        Instant now = clock.instant();
        List<DecisionInbox.Row> rows = inbox.page(scope, selected, position == null ? null : position.at(),
                position == null ? null : position.id(), size + 1);
        boolean hasMore = rows.size() > size;
        List<DecisionInbox.Row> page = hasMore ? rows.subList(0, size) : rows;
        String next = null;
        if (hasMore) {
            DecisionProposal last = page.getLast().proposal().proposal();
            next = new PageCursor(list, last.createdAt(), last.proposalId()).encode();
        }
        List<DecisionSummary> items = page.stream().map(row -> summary(scope, row, now)).toList();
        Map<InboxGroup, Long> counts = inbox.counts(scope);
        return new DecisionListResponse(items, next, hasMore, new GroupCounts(counts.get(InboxGroup.TO_APPROVE),
                counts.get(InboxGroup.MONITORING), counts.get(InboxGroup.COMPLETED)),
                ReadMeta.live(RequestCorrelationFilter.requestId(request), now, timezone(scope)));
    }

    @GetMapping("/{proposalId}")
    ResponseEntity<DecisionPresentation> detail(TrustedStoreScope scope, HttpServletRequest request,
            @PathVariable String proposalId,
            @RequestHeader(name = "If-None-Match", required = false) String ifNoneMatch) {
        StoredProposal stored = proposals.find(scope, id(proposalId)).orElseThrow(ResourceNotFoundException::new);
        DecisionProposal proposal = stored.proposal();
        UUID opportunityId = opportunityId(proposal);
        Opportunity opportunity = opportunities.find(scope, opportunityId).orElseThrow(ResourceNotFoundException::new);
        Optional<PolicyEvaluation> evaluation = policies.latest(scope, proposal.proposalId());
        String etag = etag(stored, evaluation.map(PolicyEvaluation::id).orElse(null));
        ExecutionView execution = execution(scope, proposal.proposalId());
        // An execution moves without a new revision: its state is never answered from a cached copy.
        if (etag.equals(ifNoneMatch) && execution == null) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        Instant now = clock.instant();
        ContextSnapshot snapshot = snapshots.find(scope, proposal.contextSnapshotId())
                .orElseThrow(ResourceNotFoundException::new);
        InventoryContext context = InventoryContext.of(snapshot);
        ProposalStatus status = effectiveStatus(stored, now);

        List<RevisionRef> revisions = proposals.ofOpportunity(scope, opportunityId).stream()
                .map(revision -> new RevisionRef(revision.proposal().proposalId(), revision.proposal().revision(),
                        effectiveStatus(revision, now), revision.proposal().createdAt()))
                .toList();
        ReadMeta meta = new ReadMeta(RequestCorrelationFilter.requestId(request), now, snapshot.asOf(),
                timezone(scope), etag, ReadMeta.Completeness.COMPLETE, List.of());
        DecisionPresentation presentation = new DecisionPresentation(proposal.proposalId(), opportunityId,
                proposal.revision(), revisions, status, opportunity.status(), InboxGroup.of(opportunity.status()),
                proposal.recommendation().disposition(), proposal.recommendation().summary(),
                proposal.recommendation().reasonCodes(), proposal.recommendation().uncertainties(),
                proposal.recommendation().missingRequirements(), subject(scope, opportunity), facts(context),
                action(proposal, context), excludedAds(context),
                proposal.recommendation().evidence().stream().map(EvidenceRef::evidenceId).toList(),
                evaluation.map(DecisionController::policy).orElse(null),
                new ProposerRef(proposal.proposer().kind(), proposal.proposer().name(), proposal.proposer().version()),
                proposal.createdAt(), proposal.expiresAt(), execution, allowed(scope, stored, status),
                blockedReason(scope, stored, status), meta);
        return ResponseEntity.ok().eTag(etag).body(presentation);
    }

    @RateLimited(RateLimited.Group.WORK)
    @PostMapping("/{proposalId}/approvals")
    ResponseEntity<DecisionCommandResponse> approve(
            @RequiresPermission(StorePermission.DECISION_APPROVE) TrustedStoreScope scope,
            @PathVariable String proposalId, @RequestBody RevisionRequest body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID id = id(proposalId);
        checkVersion(scope, id, ifMatch, idempotencyKey);
        return accepted(scope, approvals.approve(scope, id, body.revision(), idempotencyKey));
    }

    @RateLimited(RateLimited.Group.WORK)
    @PostMapping("/{proposalId}/rejections")
    ResponseEntity<DecisionCommandResponse> reject(
            @RequiresPermission(StorePermission.DECISION_APPROVE) TrustedStoreScope scope,
            @PathVariable String proposalId, @RequestBody RejectionRequest body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey) {
        UUID id = id(proposalId);
        checkVersion(scope, id, ifMatch, idempotencyKey);
        return accepted(scope, approvals.reject(scope, id, body.revision(), body.reason(), idempotencyKey));
    }

    /** A repeated command is answered from its key; a new one must name the version the person saw. */
    private void checkVersion(TrustedStoreScope scope, UUID proposalId, String ifMatch, String idempotencyKey) {
        if (approvals.byKey(scope, idempotencyKey).isPresent()) {
            return;
        }
        StoredProposal stored = proposals.find(scope, proposalId).orElseThrow(ResourceNotFoundException::new);
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ApprovalException(ApprovalException.Kind.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED");
        }
        String current = etag(stored, policies.latest(scope, proposalId).map(PolicyEvaluation::id).orElse(null));
        if (!current.equals(ifMatch.strip())) {
            throw new ApprovalException(ApprovalException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
        }
    }

    private static ResponseEntity<DecisionCommandResponse> accepted(TrustedStoreScope scope,
            DecisionApprovals.Result result) {
        DecisionApprovals.Approval approval = result.approval();
        String statusUrl = "/api/v1/stores/" + scope.storeId() + "/decisions/" + approval.proposalId();
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new DecisionCommandResponse(approval.id(),
                approval.id(), approval.proposalId(), approval.revision(), approval.decision(), approval.decidedAt(),
                approval.expiresAt(), statusUrl, result.replayed()));
    }

    @GetMapping("/{proposalId}/evidence")
    DecisionEvidenceResponse evidence(TrustedStoreScope scope, HttpServletRequest request,
            @PathVariable String proposalId) {
        StoredProposal stored = proposals.find(scope, id(proposalId)).orElseThrow(ResourceNotFoundException::new);
        DecisionProposal proposal = stored.proposal();
        ContextSnapshot snapshot = snapshots.find(scope, proposal.contextSnapshotId())
                .orElseThrow(ResourceNotFoundException::new);
        List<UUID> cited = proposal.recommendation().evidence().stream().map(EvidenceRef::evidenceId).toList();
        List<UUID> ids = new ArrayList<>(cited);
        snapshot.evidenceIds().stream().filter(id -> !ids.contains(id)).forEach(ids::add);
        Map<UUID, Evidence> found = new LinkedHashMap<>();
        evidence.findAll(scope, ids).forEach(item -> found.put(item.id(), item));
        List<EvidenceItem> items = ids.stream().filter(found::containsKey).map(found::get)
                .map(item -> new EvidenceItem(item.id(), item.version(), item.type(), item.kind(), item.resourceRef(),
                        item.definitionVersion(), item.content(), item.contentHash(), item.inputEvidenceIds(),
                        item.observedAt(), item.availableAt(), item.computedAt(), cited.contains(item.id())))
                .toList();
        boolean complete = items.size() == ids.size();
        Instant now = clock.instant();
        ReadMeta meta = new ReadMeta(RequestCorrelationFilter.requestId(request), now, snapshot.asOf(),
                timezone(scope), null, complete ? ReadMeta.Completeness.COMPLETE : ReadMeta.Completeness.PARTIAL,
                complete ? List.of() : List.of("EVIDENCE_MISSING"));
        return new DecisionEvidenceResponse(proposal.proposalId(), new SnapshotRef(snapshot.id(),
                snapshot.contentSchema(), snapshot.asOf(), snapshot.contentHash()), items, null, false, meta);
    }

    private DecisionSummary summary(TrustedStoreScope scope, DecisionInbox.Row row, Instant now) {
        DecisionProposal proposal = row.proposal().proposal();
        Opportunity opportunity = opportunities.find(scope, row.opportunityId()).orElseThrow();
        Optional<ContextSnapshot> snapshot = snapshots.find(scope, proposal.contextSnapshotId());
        InventoryContext context = snapshot.map(InventoryContext::of).orElse(null);
        return new DecisionSummary(proposal.proposalId(), row.opportunityId(), proposal.revision(), row.group(),
                effectiveStatus(row.proposal(), now), row.opportunityStatus(), proposal.recommendation().disposition(),
                proposal.recommendation().summary(), proposal.recommendation().reasonCodes(),
                subject(scope, opportunity), context == null ? null : facts(context),
                context == null ? null : action(proposal, context),
                policies.latest(scope, proposal.proposalId()).map(PolicyEvaluation::outcome).map(Enum::name)
                        .orElse(null),
                proposal.createdAt(), proposal.expiresAt(), opportunity.openedAt());
    }

    private SubjectRef subject(TrustedStoreScope scope, Opportunity opportunity) {
        Optional<Variant> variant = catalog.variant(scope, opportunity.subjectId());
        return new SubjectRef(opportunity.subjectType(), opportunity.subjectId(),
                variant.map(Variant::sku).orElse(null), variant.map(Variant::productName).orElse(null),
                variant.map(Variant::name).orElse(null));
    }

    private static InventoryFacts facts(InventoryContext context) {
        InventoryContext.Stock stock = context.stock();
        return new InventoryFacts(context.daysOfCover().value(), context.daysOfCover().unavailableReason(),
                context.thresholdDays(), stock == null ? null : stock.availableToSell(),
                context.unitsSold().value(), context.velocityPerDay().value(),
                stock == null ? null : stock.confirmedAt(), context.replenishmentKnown());
    }

    private static ActionRef action(DecisionProposal proposal, InventoryContext context) {
        if (!(proposal.recommendation().action() instanceof PauseAd pause)) {
            return null;
        }
        Optional<InventoryContext.AdFact> ad = context.ads().stream()
                .filter(fact -> fact.adId().equals(pause.adId())).findFirst();
        return new ActionRef(pause.type(), PauseAd.TARGET_TYPE, pause.adId(),
                ad.map(InventoryContext.AdFact::externalId).orElse(null),
                ad.map(InventoryContext.AdFact::name).orElse(null), PauseAd.DESIRED_STATUS);
    }

    private static List<ExcludedAd> excludedAds(InventoryContext context) {
        return context.ads().stream().filter(ad -> !ad.singleVariant())
                .map(ad -> new ExcludedAd(ad.adId(), ad.externalId(), ad.name(), "MULTI_PRODUCT_AD"))
                .toList();
    }

    private static PolicyRef policy(PolicyEvaluation evaluation) {
        return new PolicyRef(evaluation.id(), evaluation.policyKey(), evaluation.policyVersion(),
                evaluation.mode().name(), evaluation.outcome().name(), evaluation.decisiveCode(),
                evaluation.evaluatedAt(), evaluation.checks().stream().map(DecisionController::check).toList());
    }

    private static CheckRef check(PolicyCheck check) {
        return new CheckRef(check.name(), check.result().name(),
                check.outcome() == null ? null : check.outcome().name(), check.code(), check.facts());
    }

    /** A proposal still waiting after its expiry is presented as expired before the workflow records it. */
    static ProposalStatus effectiveStatus(StoredProposal stored, Instant now) {
        return stored.status() == ProposalStatus.PROPOSED && !now.isBefore(stored.proposal().expiresAt())
                ? ProposalStatus.EXPIRED : stored.status();
    }

    private List<String> allowed(TrustedStoreScope scope, StoredProposal stored, ProposalStatus status) {
        if (status != ProposalStatus.PROPOSED) {
            return List.of();
        }
        List<String> actions = new ArrayList<>();
        if (approvals.blocker(scope, stored, DecisionApprovals.Decision.APPROVED).isEmpty()) {
            actions.add("APPROVE");
        }
        if (approvals.blocker(scope, stored, DecisionApprovals.Decision.REJECTED).isEmpty()) {
            actions.add("REJECT");
        }
        return List.copyOf(actions);
    }

    /** Why the proposal cannot be approved now; null when it can, or when it is no longer open. */
    private String blockedReason(TrustedStoreScope scope, StoredProposal stored, ProposalStatus status) {
        return switch (status) {
            case PROPOSED -> approvals.blocker(scope, stored, DecisionApprovals.Decision.APPROVED).orElse(null);
            case EXPIRED -> PROPOSAL_EXPIRED;
            default -> null;
        };
    }

    private static UUID opportunityId(DecisionProposal proposal) {
        if (proposal.subject() instanceof OpportunitySubject subject) {
            return subject.opportunityId();
        }
        throw new ResourceNotFoundException();
    }

    static String etag(StoredProposal stored, UUID latestEvaluation) {
        String raw = stored.payloadHash() + "|" + stored.status() + "|" + stored.statusChangedAt() + "|"
                + latestEvaluation;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(raw.getBytes(StandardCharsets.UTF_8));
            return "\"" + HexFormat.of().formatHex(digest, 0, 16) + "\"";
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String timezone(TrustedStoreScope scope) {
        return storeAccess.store(scope).timezone();
    }

    private static InboxGroup group(String value) {
        if (value == null || value.isBlank()) {
            return InboxGroup.TO_APPROVE;
        }
        try {
            return InboxGroup.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("INVALID_GROUP");
        }
    }

    private static UUID id(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException();
        }
    }

    record RevisionRequest(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision) {
    }

    record RejectionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            @Schema(description = "Why the proposal is rejected; at most 1000 characters") String reason) {
    }

    /** {@code 202}: the decision is stored; whether the ad is paused shows in the decision's execution state. */
    record DecisionCommandResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID approvalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID operationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DecisionApprovals.Decision decision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant decidedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String statusUrl,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replayed) {
    }

    record DecisionListResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<DecisionSummary> items,
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) GroupCounts counts,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReadMeta meta) {
    }

    record GroupCounts(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long toApprove,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long monitoring,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long completed) {
    }

    record DecisionSummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID opportunityId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) InboxGroup group,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposalStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OpportunityStatus opportunityStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DecisionDisposition disposition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String businessSummary,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> reasonCodes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SubjectRef subject,
            InventoryFacts facts,
            ActionRef action,
            String policyOutcome,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant openedAt) {
    }

    record DecisionPresentation(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID opportunityId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<RevisionRef> revisions,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposalStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OpportunityStatus opportunityStatus,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) InboxGroup group,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DecisionDisposition disposition,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String businessSummary,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> reasonCodes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> uncertainties,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> missingRequirements,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SubjectRef subject,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) InventoryFacts facts,
            ActionRef action,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ExcludedAd> excludedAds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> evidenceRefs,
            PolicyRef policyEvaluation,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposerRef proposer,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant expiresAt,
            @Schema(description = "The person's decision and what followed; null until someone decides.")
            ExecutionView execution,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> allowedActions,
            String blockedReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReadMeta meta) {
    }

    /**
     * What happened after a person decided (B-44, ADR-0020/0021): the decision, the authorization, the ledger row and
     * the outcome. {@code status} is the ledger's: APPLIED and UNKNOWN are not a confirmed effect.
     */
    private ExecutionView execution(TrustedStoreScope scope, UUID proposalId) {
        Optional<DecisionApprovals.Approval> approval = approvals.ofProposal(scope, proposalId);
        if (approval.isEmpty()) {
            return null;
        }
        DecisionApprovals.Approval decided = approval.get();
        Optional<AuthorizedAction> action = decided.decision() == DecisionApprovals.Decision.APPROVED
                ? authority.latest(scope, decided.id()) : Optional.empty();
        Optional<ActionExecution> row = action.flatMap(a -> ledger.byKey(scope, a.idempotencyKey()));
        Optional<ActionOutcomes.Outcome> outcome = row.flatMap(r -> outcomes.ofExecution(scope, r.id()));
        return new ExecutionView(decided.decision(), decided.decidedAt(), decided.reason(), decided.expiresAt(),
                action.map(AuthorizedAction::issuedAt).orElse(null), row.map(ActionExecution::id).orElse(null),
                row.map(r -> r.status().name()).orElse(null), row.map(ActionExecution::resultCode).orElse(null),
                row.map(ActionExecution::attempts).orElse(0), row.map(ActionExecution::updatedAt).orElse(null),
                outcome.map(o -> new OutcomeView(o.authorship().name(), o.verifiedAt(), o.recordedAt(),
                        o.observations().stream().map(obs -> new ObservationView(obs.checkpoint().name(), obs.dueAt(),
                                obs.status().name(), values(obs.values()), obs.limitations(), obs.observedAt()))
                                .toList())).orElse(null));
    }

    private static Map<String, String> values(JsonNode node) {
        Map<String, String> values = new java.util.TreeMap<>();
        if (node != null) {
            node.properties().forEach(entry -> values.put(entry.getKey(),
                    entry.getValue().isNull() ? null : entry.getValue().asString()));
        }
        return values;
    }

    record ExecutionView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) DecisionApprovals.Decision decision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant decidedAt,
            String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant approvalExpiresAt,
            Instant authorizedAt,
            UUID executionId,
            @Schema(description = "PLANNED, IN_PROGRESS, APPLIED, VERIFIED, NOOP, UNKNOWN, FAILED or CONFLICT")
            String status,
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int attempts,
            Instant updatedAt,
            OutcomeView outcome) {
    }

    record OutcomeView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "OPERATOR, UNCERTAIN or NONE")
            String authorship,
            Instant verifiedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant recordedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ObservationView> observations) {
    }

    record ObservationView(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String checkpoint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant dueAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "PENDING, OBSERVED or INCONCLUSIVE")
            String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> values,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<String> limitations,
            Instant observedAt) {
    }

    record RevisionRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int revision,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposalStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt) {
    }

    record SubjectRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            String sku,
            String productName,
            String variantName) {
    }

    /** The inventory facts the analysis used, from its snapshot. Null values were unavailable. */
    record InventoryFacts(
            BigDecimal daysOfCover,
            String daysOfCoverUnavailableReason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal thresholdDays,
            Integer availableToSell,
            BigDecimal unitsSold,
            BigDecimal velocityPerDay,
            Instant stockConfirmedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean replenishmentKnown) {
    }

    record ActionRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String targetType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID targetId,
            String targetExternalId,
            String targetName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String desiredStatus) {
    }

    record ExcludedAd(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID adId,
            String externalId,
            String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reason) {
    }

    record PolicyRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID evaluationId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String policyKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String policyVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String mode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String outcome,
            String decisiveCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant evaluatedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CheckRef> checks) {
    }

    record CheckRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String result,
            String outcome,
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> facts) {
    }

    record ProposerRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ProposerKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String name,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String version) {
    }

    record DecisionEvidenceResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID proposalId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) SnapshotRef snapshot,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<EvidenceItem> items,
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReadMeta meta) {
    }

    record SnapshotRef(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentSchema,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant asOf,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentHash) {
    }

    record EvidenceItem(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int version,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EvidenceType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) EvidenceKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String resourceRef,
            String definitionVersion,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) JsonNode content,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentHash,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<UUID> inputEvidenceIds,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant observedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant availableAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant computedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean citedByProposal) {
    }
}
