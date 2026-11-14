package com.ecommerceagent.web;

import com.ecommerceagent.identity.StoreAccessDeniedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    ResponseEntity<ApiError> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError("NOT_FOUND", "The resource does not exist or is not available.", false));
    }

    @ExceptionHandler(StoreAccessDeniedException.class)
    ResponseEntity<ApiError> forbidden() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError("FORBIDDEN", "You do not have permission to perform this operation.", false));
    }
}
