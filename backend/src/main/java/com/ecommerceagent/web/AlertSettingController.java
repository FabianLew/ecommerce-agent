package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.automation.notifications.AlertSettingException;
import com.ecommerceagent.automation.notifications.AlertSettings;
import com.ecommerceagent.identity.RegisteredUser;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The store's alert deputy (ADR-0026 point 5): read by every member, set or cleared only by a member with
 * {@code STORE_MANAGE}, from the version seen.
 */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/alert-settings")
class AlertSettingController {

    private final AlertSettings alerts;

    AlertSettingController(AlertSettings alerts) {
        this.alerts = alerts;
    }

    @GetMapping
    ResponseEntity<AlertSettingResponse> alertSettings(TrustedStoreScope scope) {
        return respond(scope, alerts.setting(scope));
    }

    @PutMapping
    ResponseEntity<AlertSettingResponse> changeAlertSettings(TrustedStoreScope scope,
            @RequestBody AlertSettingRequest body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        if (!scope.has(StorePermission.STORE_MANAGE)) {
            throw new StoreAccessDeniedException(scope.storeId(), StorePermission.STORE_MANAGE);
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new AlertSettingException(AlertSettingException.Kind.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED");
        }
        return respond(scope, alerts.change(scope, body.deputyUserId(), version(ifMatch)));
    }

    private ResponseEntity<AlertSettingResponse> respond(TrustedStoreScope scope,
            AlertSettings.Setting setting) {
        return ResponseEntity.ok().eTag("\"alert-setting-" + setting.version() + "\"")
                .body(AlertSettingResponse.from(setting, alerts.candidates(scope), scope));
    }

    private static int version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.matches("\"alert-setting-[0-9]{1,9}\"")) {
            return Integer.parseInt(value.substring(15, value.length() - 1));
        }
        throw new AlertSettingException(AlertSettingException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
    }

    record AlertSettingRequest(
            @Schema(description = "A member who may approve decisions; null clears the deputy") UUID deputyUserId) {
    }

    record CandidateResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID userId,
            String displayName) {
    }

    record AlertSettingResponse(
            UUID deputyUserId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "False when the deputy lost the permission or the membership") boolean deputyActive,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0 until the first change") int version,
            UUID changedBy,
            Instant changedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Members who may approve decisions and are not owners")
            List<CandidateResponse> candidates,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canChange) {

        static AlertSettingResponse from(AlertSettings.Setting setting, List<RegisteredUser> candidates,
                TrustedStoreScope scope) {
            return new AlertSettingResponse(setting.deputyUserId(), setting.deputyActive(), setting.version(),
                    setting.changedBy(), setting.changedAt(),
                    candidates.stream().map(user -> new CandidateResponse(user.id(), user.displayName())).toList(),
                    scope.has(StorePermission.STORE_MANAGE));
        }
    }
}
