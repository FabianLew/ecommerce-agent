package com.ecommerceagent.web;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/** List envelope from document 04 §3; small lists are returned complete, without a cursor. */
record ListResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<T> items,
        String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean hasMore) {

    static <T> ListResponse<T> complete(List<T> items) {
        return new ListResponse<>(items, null, false);
    }
}
