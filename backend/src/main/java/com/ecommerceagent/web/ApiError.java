package com.ecommerceagent.web;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Error body from document 04 §3; optional fields are omitted when not applicable. */
@JsonInclude(JsonInclude.Include.NON_NULL)
record ApiError(String code, String message, boolean retryable) {
}
