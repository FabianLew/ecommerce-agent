package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.automation.execution.ActionLevel;
import com.ecommerceagent.automation.execution.ActionPermissionException;
import com.ecommerceagent.automation.execution.ActionPermissions;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store's permission per action type (ADR-0025 point 2): read by every member, changed only from the version
 * seen. The response carries the effective level and each cap, so the settings page shows why a level is lower.
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/action-permissions/{actionType}")
class ActionPermissionController {

    private final ActionPermissions permissions;

    ActionPermissionController(ActionPermissions permissions) {
        this.permissions = permissions;
    }

    @GetMapping
    ResponseEntity<ActionPermissionResponse> actionPermission(TrustedStoreScope scope,
            @PathVariable String actionType) {
        return respond(scope, permissions.permission(scope, actionType));
    }

    @PutMapping
    ResponseEntity<ActionPermissionResponse> changeActionPermission(TrustedStoreScope scope,
            @PathVariable String actionType, @RequestBody ActionPermissionRequest body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        ActionPermissions.Permission current = permissions.permission(scope, actionType);
        if (body.level() == null) {
            throw new ActionPermissionException(ActionPermissionException.Kind.INVALID, "LEVEL_REQUIRED");
        }
        boolean raising = body.level().compareTo(current.level()) > 0;
        boolean allowed = raising ? ActionPermissions.mayRaise(scope) : ActionPermissions.mayLower(scope);
        if (!allowed) {
            throw new StoreAccessDeniedException(scope.storeId(), StorePermission.STORE_MANAGE);
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new ActionPermissionException(ActionPermissionException.Kind.PRECONDITION_REQUIRED,
                    "IF_MATCH_REQUIRED");
        }
        return respond(scope, permissions.change(scope, actionType, body.level(), body.reason(), version(ifMatch)));
    }

    private ResponseEntity<ActionPermissionResponse> respond(TrustedStoreScope scope,
            ActionPermissions.Permission permission) {
        ActionPermissions.Effective effective = permissions.effective(scope, permission.actionType());
        return ResponseEntity.ok().eTag(etag(permission))
                .body(ActionPermissionResponse.from(permission, effective, scope));
    }

    private static String etag(ActionPermissions.Permission permission) {
        return "\"permission-" + permission.version() + "\"";
    }

    private static int version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.matches("\"permission-[0-9]{1,9}\"")) {
            return Integer.parseInt(value.substring(12, value.length() - 1));
        }
        throw new ActionPermissionException(ActionPermissionException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
    }

    record ActionPermissionRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActionLevel level,
            @Schema(description = "Required when raising the level, at most 500 characters") String reason) {
    }

    record CapResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActionPermissions.Source source,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) ActionLevel level,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code) {

        static List<CapResponse> of(ActionPermissions.Effective effective) {
            return effective.caps().stream().map(cap -> new CapResponse(cap.source(), cap.level(), cap.code()))
                    .toList();
        }
    }

    record ActionPermissionResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String actionType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "The store's own level") ActionLevel level,
            String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0 until the first change") int version,
            UUID changedBy,
            Instant changedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "The lowest of the store's level and every cap") ActionLevel effectiveLevel,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<CapResponse> caps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canRaise,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canLower) {

        static ActionPermissionResponse from(ActionPermissions.Permission permission,
                ActionPermissions.Effective effective, TrustedStoreScope scope) {
            return new ActionPermissionResponse(permission.actionType(), permission.level(), permission.reason(),
                    permission.version(), permission.changedBy(), permission.changedAt(), effective.level(),
                    CapResponse.of(effective), ActionPermissions.mayRaise(scope), ActionPermissions.mayLower(scope));
        }
    }
}
