package com.ecommerceagent.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Error body from document 04 §3: {@code {code, message, fieldErrors, retryable, requestId, currentVersion}}.
 * Optional fields are omitted when not applicable. Messages are safe descriptions, never raw exceptions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ApiError(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message,
        List<FieldError> fieldErrors,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean retryable,
        String requestId,
        String currentVersion) {

    static ApiError of(String code, String message, boolean retryable, String requestId) {
        return new ApiError(code, message, null, retryable, requestId, null);
    }

    record FieldError(@Schema(requiredMode = Schema.RequiredMode.REQUIRED) String field, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code, @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String message) {
    }
}
