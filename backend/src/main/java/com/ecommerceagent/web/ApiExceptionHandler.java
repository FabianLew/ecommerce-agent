package com.ecommerceagent.web;

import java.util.List;

import com.ecommerceagent.automation.OnboardingConflictException;
import com.ecommerceagent.automation.execution.ActionPermissionException;
import com.ecommerceagent.automation.notifications.AlertSettingException;
import com.ecommerceagent.automation.execution.MutationStopException;
import com.ecommerceagent.automation.guardian.ApprovalException;
import com.ecommerceagent.businessprofile.BusinessProfileException;
import com.ecommerceagent.commerce.AdMappingException;
import com.ecommerceagent.aigovernance.AiReconciliationException;
import com.ecommerceagent.identity.PlatformOperatorDeniedException;
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

    @ExceptionHandler(AdMappingException.class)
    ResponseEntity<ApiError> adMapping(HttpServletRequest request, AdMappingException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND -> error(request, HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "The resource does not exist or is not available.", false);
            case VERSION_CONFLICT -> error(request, HttpStatus.CONFLICT, "MAPPING_CHANGED",
                    "The mapping was changed by someone else. Reload and check it again.", false);
            case INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                    "VALIDATION_FAILED", "Correct the highlighted fields.",
                    List.of(new ApiError.FieldError(exception.field(), exception.code(), exception.getMessage())),
                    false, RequestCorrelationFilter.requestId(request), null));
        };
    }

    @ExceptionHandler(ApprovalException.class)
    ResponseEntity<ApiError> approval(ApprovalException exception, HttpServletRequest request) {
        HttpStatus status = switch (exception.kind()) {
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case INVALID -> HttpStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpStatus.FORBIDDEN;
            case CONFLICT -> HttpStatus.CONFLICT;
            case PRECONDITION_FAILED -> HttpStatus.PRECONDITION_FAILED;
            case PRECONDITION_REQUIRED -> HttpStatus.PRECONDITION_REQUIRED;
        };
        String message = switch (exception.kind()) {
            case PRECONDITION_FAILED, PRECONDITION_REQUIRED ->
                    "The decision changed since it was shown. Reload it and decide again.";
            case NOT_FOUND -> "The resource does not exist or is not available.";
            default -> "The decision cannot be recorded now.";
        };
        return error(request, status, exception.code(), message, false);
    }

    @ExceptionHandler(MutationStopException.class)
    ResponseEntity<ApiError> mutationStop(MutationStopException exception, HttpServletRequest request) {
        return switch (exception.kind()) {
            case INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                    "VALIDATION_FAILED", "Correct the highlighted fields.",
                    List.of(new ApiError.FieldError("reason", exception.code(), exception.getMessage())), false,
                    RequestCorrelationFilter.requestId(request), null));
            case PRECONDITION_FAILED -> error(request, HttpStatus.PRECONDITION_FAILED, exception.code(),
                    "The stop was changed by someone else. Reload it and try again.", false);
            case PRECONDITION_REQUIRED -> error(request, HttpStatus.PRECONDITION_REQUIRED, exception.code(),
                    "Reload the stop and try again.", false);
        };
    }

    @ExceptionHandler(ActionPermissionException.class)
    ResponseEntity<ApiError> actionPermission(ActionPermissionException exception, HttpServletRequest request) {
        return switch (exception.kind()) {
            case INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                    "VALIDATION_FAILED", "Correct the highlighted fields.",
                    List.of(new ApiError.FieldError(exception.code().startsWith("LEVEL") ? "level" : "reason",
                            exception.code(), exception.getMessage())), false,
                    RequestCorrelationFilter.requestId(request), null));
            case NOT_FOUND -> error(request, HttpStatus.NOT_FOUND, exception.code(),
                    "The resource does not exist or is not available.", false);
            case CONFLICT -> error(request, HttpStatus.CONFLICT, exception.code(),
                    "The store is not ready for this level yet.", false);
            case PRECONDITION_FAILED -> error(request, HttpStatus.PRECONDITION_FAILED, exception.code(),
                    "The permission was changed by someone else. Reload it and try again.", false);
            case PRECONDITION_REQUIRED -> error(request, HttpStatus.PRECONDITION_REQUIRED, exception.code(),
                    "Reload the permission and try again.", false);
        };
    }

    @ExceptionHandler(AlertSettingException.class)
    ResponseEntity<ApiError> alertSetting(AlertSettingException exception, HttpServletRequest request) {
        return switch (exception.kind()) {
            case INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                    "VALIDATION_FAILED", "Correct the highlighted fields.",
                    List.of(new ApiError.FieldError("deputyUserId", exception.code(), exception.getMessage())), false,
                    RequestCorrelationFilter.requestId(request), null));
            case PRECONDITION_FAILED -> error(request, HttpStatus.PRECONDITION_FAILED, exception.code(),
                    "The setting was changed by someone else. Reload it and try again.", false);
            case PRECONDITION_REQUIRED -> error(request, HttpStatus.PRECONDITION_REQUIRED, exception.code(),
                    "Reload the setting and try again.", false);
        };
    }

    @ExceptionHandler(BusinessProfileException.class)
    ResponseEntity<ApiError> businessProfile(HttpServletRequest request, BusinessProfileException exception) {
        return switch (exception.reason()) {
            case NOT_FOUND -> error(request, HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "The resource does not exist or is not available.", false);
            case VERSION_CONFLICT -> error(request, HttpStatus.CONFLICT, "PROFILE_CHANGED",
                    "The profile was changed by someone else. Reload and check it again.", false);
            case INVALID -> ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(new ApiError(
                    "VALIDATION_FAILED", "Correct the highlighted fields.",
                    List.of(new ApiError.FieldError(exception.field(), exception.code(), exception.getMessage())),
                    false, RequestCorrelationFilter.requestId(request), null));
        };
    }

    @ExceptionHandler(OnboardingConflictException.class)
    ResponseEntity<ApiError> onboarding(HttpServletRequest request, OnboardingConflictException exception) {
        return exception.conflict()
                ? error(request, HttpStatus.CONFLICT, "ONBOARDING_CHANGED",
                        "The onboarding progress was changed elsewhere. Reload the page.", false)
                : error(request, HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED", exception.getMessage(), false);
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

    @ExceptionHandler(PlatformOperatorDeniedException.class)
    ResponseEntity<ApiError> notPlatformOperator(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this operation.", false);
    }

    @ExceptionHandler(AiReconciliationException.class)
    ResponseEntity<ApiError> aiReconciliation(AiReconciliationException exception, HttpServletRequest request) {
        return switch (exception.kind()) {
            case NOT_FOUND -> error(request, HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "The resource does not exist or is not available.", false);
            case CONFLICT -> error(request, HttpStatus.CONFLICT, "RECONCILIATION_CONFLICT",
                    "The call is not open for this decision, or a different one was already recorded.", false);
            case INVALID -> error(request, HttpStatus.UNPROCESSABLE_CONTENT, "VALIDATION_FAILED",
                    "Give an outcome, a reason of 1 to 500 characters, and a cost only for CHARGED.", false);
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
