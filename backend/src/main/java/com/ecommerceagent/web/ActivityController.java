package com.ecommerceagent.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.identity.AuditRecord;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activity History (04 §9, ADR-0014 point 8): a projection of the store's audit trail for an allowlist of
 * operational actions. Access-denied events stay in the audit only.
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/activities")
class ActivityController {

    enum ActivityType {
        DETECTION,
        RECOMMENDATION,
        POLICY,
        INTEGRATION
    }

    enum ActorType {
        USER,
        SYSTEM
    }

    /** Audit actions shown as activity, with their type. Anything else is not listed. */
    static final Map<String, ActivityType> TYPES = Map.ofEntries(
            Map.entry("OPPORTUNITY_OPENED", ActivityType.DETECTION),
            Map.entry("OPPORTUNITY_STATUS_CHANGED", ActivityType.DETECTION),
            Map.entry("PROPOSAL_CREATED", ActivityType.RECOMMENDATION),
            Map.entry("PROPOSAL_INVALID", ActivityType.RECOMMENDATION),
            Map.entry("PROPOSAL_STATUS_CHANGED", ActivityType.RECOMMENDATION),
            Map.entry("POLICY_EVALUATED", ActivityType.POLICY),
            Map.entry("INTEGRATION_CONNECTION_CREATED", ActivityType.INTEGRATION),
            Map.entry("INTEGRATION_CREDENTIALS_UPDATED", ActivityType.INTEGRATION),
            Map.entry("INTEGRATION_CHECK_COMPLETED", ActivityType.INTEGRATION),
            Map.entry("INTEGRATION_SYNC_STARTED", ActivityType.INTEGRATION),
            Map.entry("INTEGRATION_SYNC_FINISHED", ActivityType.INTEGRATION),
            Map.entry("INTEGRATION_WEBHOOK_SECRET_UPDATED", ActivityType.INTEGRATION));

    private final AuditTrail audit;
    private final StoreAccess storeAccess;
    private final Clock clock;

    ActivityController(AuditTrail audit, StoreAccess storeAccess, Clock clock) {
        this.audit = audit;
        this.storeAccess = storeAccess;
        this.clock = clock;
    }

    @GetMapping
    ActivityListResponse list(TrustedStoreScope scope, HttpServletRequest request,
            @RequestParam(required = false) String type, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        ActivityType selected = type(type);
        int size = PageCursor.limit(limit);
        String list = "activities:" + (selected == null ? "ALL" : selected.name());
        PageCursor position = PageCursor.decode(cursor, list);
        List<String> actions = TYPES.entrySet().stream()
                .filter(entry -> selected == null || entry.getValue() == selected)
                .map(Map.Entry::getKey).sorted().toList();
        List<AuditRecord> records = audit.ofStore(scope, actions, position == null ? null : position.at(),
                position == null ? null : position.id(), size + 1);
        boolean hasMore = records.size() > size;
        List<AuditRecord> page = hasMore ? records.subList(0, size) : records;
        String next = hasMore ? new PageCursor(list, page.getLast().occurredAt(), page.getLast().id()).encode() : null;
        Instant now = clock.instant();
        return new ActivityListResponse(page.stream().map(ActivityController::summary).toList(), next, hasMore,
                ReadMeta.live(RequestCorrelationFilter.requestId(request), now,
                        storeAccess.store(scope).timezone()));
    }

    private static ActivitySummary summary(AuditRecord record) {
        Actor actor = record.actorUserId() != null
                ? new Actor(ActorType.USER, record.actorUserId(), record.actorDisplayName(), null)
                : new Actor(ActorType.SYSTEM, null, null, record.actorService());
        return new ActivitySummary(record.id(), record.occurredAt(), TYPES.get(record.action()), record.action(),
                actor, record.resourceType(), record.resourceId(), record.details());
    }

    private static ActivityType type(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return ActivityType.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidQueryException("INVALID_TYPE");
        }
    }

    record ActivityListResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<ActivitySummary> items,
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ReadMeta meta) {
    }

    record ActivitySummary(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant occurredAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActivityType type,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String action,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Actor actor,
            String resourceType,
            String resourceId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Map<String, String> details) {
    }

    record Actor(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActorType type,
            UUID userId,
            String displayName,
            String service) {
    }
}
