package com.ecommerceagent.web;

import java.util.List;

import com.ecommerceagent.identity.StoreAccessDeniedException;
import com.ecommerceagent.integration.IntegrationConflictException;
import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.InvalidIntegrationRequestException;
import com.ecommerceagent.integration.SyncInProgressException;
import com.ecommerceagent.integration.WebhookRejectedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the 04 §3 error format with the request's {@code requestId}. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class,
            IntegrationNotFoundException.class})
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "The resource does not exist or is not available.",
                false);
    }

    @ExceptionHandler(StoreAccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this operation.", false);
    }

    @ExceptionHandler(InvalidIntegrationRequestException.class)
    ResponseEntity<ApiError> invalid(HttpServletRequest request, InvalidIntegrationRequestException exception) {
        ApiError error = new ApiError("VALIDATION_FAILED", "Correct the highlighted fields.",
                List.of(new ApiError.FieldError(exception.field(), exception.code(), exception.getMessage())), false,
                RequestCorrelationFilter.requestId(request), null);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(error);
    }

    @ExceptionHandler(IntegrationConflictException.class)
    ResponseEntity<ApiError> conflict(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "ALREADY_CONNECTED",
                "This store already has a connection for the provider.", false);
    }

    @ExceptionHandler(SyncInProgressException.class)
    ResponseEntity<ApiError> syncInProgress(HttpServletRequest request) {
        return error(request, HttpStatus.CONFLICT, "SYNC_IN_PROGRESS",
                "An import for this integration is already running.", true);
    }

    /** Webhook rejections carry no detail beyond the code (ADR-0009). */
    @ExceptionHandler(WebhookRejectedException.class)
    ResponseEntity<ApiError> webhookRejected(HttpServletRequest request, WebhookRejectedException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND -> error(request, HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "The resource does not exist or is not available.", false);
            case SIGNATURE_INVALID -> error(request, HttpStatus.UNAUTHORIZED, "WEBHOOK_SIGNATURE_INVALID",
                    "The webhook signature does not match the saved secret.", false);
            case PAYLOAD_TOO_LARGE -> error(request, HttpStatus.CONTENT_TOO_LARGE, "PAYLOAD_TOO_LARGE",
                    "The webhook body is too large.", false);
        };
    }

    @ExceptionHandler(InvalidQueryException.class)
    ResponseEntity<ApiError> invalidQuery(HttpServletRequest request, InvalidQueryException exception) {
        return error(request, HttpStatus.BAD_REQUEST, exception.code(), "A query parameter is not valid.", false);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> malformed(HttpServletRequest request) {
        return error(request, HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "The request body is not valid.", false);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> methodNotAllowed(HttpServletRequest request) {
        return error(request, HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "This operation is not supported for the resource.", false);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(HttpServletRequest request, Exception exception) {
        // Full details stay in the log (with requestId); the client gets a safe message only.
        log.error("Unhandled exception", exception);
        return error(request, HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR",
                "Unexpected error. Quote the requestId when reporting it.", true);
    }

    private static ResponseEntity<ApiError> error(HttpServletRequest request, HttpStatus status, String code,
            String message, boolean retryable) {
        return ResponseEntity.status(status)
                .body(ApiError.of(code, message, retryable, RequestCorrelationFilter.requestId(request)));
    }

}
