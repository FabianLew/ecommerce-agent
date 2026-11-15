package com.ecommerceagent.web;

import java.time.Instant;
import java.util.UUID;

import com.ecommerceagent.automation.execution.MutationStopException;
import com.ecommerceagent.automation.execution.MutationStops;
import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The store's mutation stop (B-40, ADR-0020 point 7): read by every member, changed only from the version seen. */
@RestController
@RequestMapping("/api/v1/stores/{storeId}/mutation-stop")
class MutationStopController {

    private final MutationStops stops;

    MutationStopController(MutationStops stops) {
        this.stops = stops;
    }

    @GetMapping
    ResponseEntity<MutationStopResponse> mutationStop(TrustedStoreScope scope) {
        MutationStops.State state = stops.state(scope);
        return ResponseEntity.ok().eTag(etag(state)).body(MutationStopResponse.from(state, scope));
    }

    @PostMapping
    ResponseEntity<MutationStopResponse> changeMutationStop(TrustedStoreScope scope,
            @RequestBody MutationStopRequest body,
            @RequestHeader(name = "If-Match", required = false) String ifMatch) {
        boolean allowed = body.stopped() ? MutationStops.mayStop(scope) : scope.has(StorePermission.STORE_MANAGE);
        if (!allowed) {
            throw new StoreAccessDeniedException(scope.storeId(), StorePermission.STORE_MANAGE);
        }
        if (ifMatch == null || ifMatch.isBlank()) {
            throw new MutationStopException(MutationStopException.Kind.PRECONDITION_REQUIRED, "IF_MATCH_REQUIRED");
        }
        MutationStops.State state = stops.change(scope, body.stopped(), body.reason(), version(ifMatch));
        return ResponseEntity.ok().eTag(etag(state)).body(MutationStopResponse.from(state, scope));
    }

    private static String etag(MutationStops.State state) {
        return "\"stop-" + state.version() + "\"";
    }

    private static int version(String ifMatch) {
        String value = ifMatch.strip();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        if (value.matches("\"stop-[0-9]{1,9}\"")) {
            return Integer.parseInt(value.substring(6, value.length() - 1));
        }
        throw new MutationStopException(MutationStopException.Kind.PRECONDITION_FAILED, "VERSION_CHANGED");
    }

    record MutationStopRequest(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "true stops writes, false resumes them")
            boolean stopped,
            @Schema(description = "Required when stopping, at most 500 characters") String reason) {
    }

    record MutationStopResponse(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean stopped,
            String reason,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, description = "0 until the first change") int version,
            UUID changedBy,
            Instant changedAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED,
                    description = "Operators stopped all writes; the store cannot resume them") boolean globallyStopped,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canStop,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canResume) {

        static MutationStopResponse from(MutationStops.State state, TrustedStoreScope scope) {
            return new MutationStopResponse(state.stopped(), state.reason(), state.version(), state.changedBy(),
                    state.changedAt(), state.globallyStopped(), MutationStops.mayStop(scope),
                    scope.has(StorePermission.STORE_MANAGE));
        }
    }
}
