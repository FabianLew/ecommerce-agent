package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.ecommerceagent.automation.notifications.Notification;
import com.ecommerceagent.automation.notifications.NotificationKind;
import com.ecommerceagent.automation.notifications.OperatorNotifier;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** In-app notifications (B-41, ADR-0021): the store's notifications with this person's read marks. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/notifications")
class NotificationController {

    private final OperatorNotifier notifier;

    NotificationController(OperatorNotifier notifier) {
        this.notifier = notifier;
    }

    @GetMapping
    NotificationListResponse notifications(TrustedStoreScope scope, @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer limit) {
        UUID userId = person(scope);
        int size = PageCursor.limit(limit);
        PageCursor position = PageCursor.decode(cursor, "notifications");
        List<Notification> rows = notifier.list(scope, userId, position == null ? null : position.at(),
                position == null ? null : position.id(), size + 1);
        boolean hasMore = rows.size() > size;
        List<Notification> page = hasMore ? rows.subList(0, size) : rows;
        String next = hasMore ? new PageCursor("notifications", page.getLast().createdAt(), page.getLast().id())
                .encode() : null;
        return new NotificationListResponse(page.stream().map(NotificationResponse::from).toList(), next, hasMore,
                notifier.unreadCount(scope, userId));
    }

    @PostMapping("/{notificationId}/read")
    ResponseEntity<Void> markRead(TrustedStoreScope scope, @PathVariable String notificationId) {
        UUID id;
        try {
            id = UUID.fromString(notificationId);
        } catch (IllegalArgumentException e) {
            throw new ResourceNotFoundException();
        }
        if (!notifier.markRead(scope, person(scope), id)) {
            throw new ResourceNotFoundException();
        }
        return ResponseEntity.noContent().build();
    }

    private static UUID person(TrustedStoreScope scope) {
        return scope.userId().orElseThrow(ResourceNotFoundException::new);
    }

    record NotificationResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NotificationKind kind,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) NotificationKind.Severity severity,
            String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Backend-set values the UI renders: adExternalId, adName, sku, variantName")
            Map<String, String> parameters,
            UUID opportunityId,
            UUID proposalId,
            UUID executionId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean read) {

        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.id(), n.kind(), n.severity(), n.code(), n.parameters(),
                    n.opportunityId(), n.proposalId(), n.executionId(), n.createdAt(), n.read());
        }
    }

    record NotificationListResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<NotificationResponse> items,
            String nextCursor,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long unreadCount) {
    }
}
