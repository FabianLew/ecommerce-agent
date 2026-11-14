package com.ecommerceagent.web;

import com.ecommerceagent.identity.StoreAccessDeniedException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps exceptions to the 04 §3 error format with the request's {@code requestId}. */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler({ResourceNotFoundException.class, NoResourceFoundException.class})
    ResponseEntity<ApiError> notFound(HttpServletRequest request) {
        return error(request, HttpStatus.NOT_FOUND, "NOT_FOUND", "The resource does not exist or is not available.",
                false);
    }

    @ExceptionHandler(StoreAccessDeniedException.class)
    ResponseEntity<ApiError> forbidden(HttpServletRequest request) {
        return error(request, HttpStatus.FORBIDDEN, "FORBIDDEN",
                "You do not have permission to perform this operation.", false);
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
