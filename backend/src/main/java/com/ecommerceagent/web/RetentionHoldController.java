package com.ecommerceagent.web;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.ecommerceagent.identity.RetentionHolds;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store's legal hold on retention (ADR-0022 point 7): read by every member, set and released by a manager. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/retention-hold")
class RetentionHoldController {

    private final RetentionHolds holds;

    RetentionHoldController(RetentionHolds holds) {
        this.holds = holds;
    }

    @GetMapping
    RetentionHoldResponse retentionHold(TrustedStoreScope scope) {
        return RetentionHoldResponse.of(holds.hold(scope).orElse(null), scope);
    }

    @PutMapping
    ResponseEntity<?> setRetentionHold(@RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope,
            @RequestBody RetentionHoldRequest body, HttpServletRequest request) {
        String reason = body.reason() == null ? "" : body.reason().strip();
        if (reason.isEmpty() || reason.length() > 500) {
            String code = reason.isEmpty() ? "REASON_REQUIRED" : "REASON_TOO_LONG";
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError("VALIDATION_FAILED",
                    "Correct the highlighted fields.", List.of(new ApiError.FieldError("reason", code, code)), false,
                    RequestCorrelationFilter.requestId(request), null));
        }
        return ResponseEntity.ok(RetentionHoldResponse.of(holds.set(scope, reason), scope));
    }

    @DeleteMapping
    RetentionHoldResponse releaseRetentionHold(
            @RequiresPermission(StorePermission.STORE_MANAGE) TrustedStoreScope scope) {
        holds.release(scope);
        return RetentionHoldResponse.of(null, scope);
    }

    record RetentionHoldRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "1 to 500 characters") String reason) {
    }

    record RetentionHoldResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean held,
            String reason,
            UUID setBy,
            Instant setAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canChange) {

        static RetentionHoldResponse of(RetentionHolds.Hold hold, TrustedStoreScope scope) {
            boolean canChange = scope.has(StorePermission.STORE_MANAGE);
            return hold == null ? new RetentionHoldResponse(false, null, null, null, canChange)
                    : new RetentionHoldResponse(true, hold.reason(), hold.setBy(), hold.setAt(), canChange);
        }
    }
}
