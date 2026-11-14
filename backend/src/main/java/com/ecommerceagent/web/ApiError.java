package com.ecommerceagent.web;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Error body from document 04 §3: {@code {code, message, fieldErrors, retryable, requestId, currentVersion}}.
 * Optional fields are omitted when not applicable. Messages are safe descriptions, never raw exceptions.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ApiError(String code, String message, List<FieldError> fieldErrors, boolean retryable, String requestId,
        String currentVersion) {

    static ApiError of(String code, String message, boolean retryable, String requestId) {
        return new ApiError(code, message, null, retryable, requestId, null);
    }

    record FieldError(String field, String code, String message) {
    }
}
