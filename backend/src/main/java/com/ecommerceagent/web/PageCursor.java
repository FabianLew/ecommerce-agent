package com.ecommerceagent.web;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.UUID;

/**
 * Opaque keyset cursor of a list ordered by time and id, newest first (ADR-0014 point 4). It binds the list it
 * came from, so a cursor of one group or filter is rejected by another.
 */
record PageCursor(String list, Instant at, UUID id) {

    static final int DEFAULT_LIMIT = 25;
    static final int MAX_LIMIT = 100;

    String encode() {
        String raw = list + "|" + at + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** Null for no cursor; a cursor that is malformed or belongs to another list fails with INVALID_CURSOR. */
    static PageCursor decode(String value, String list) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String[] parts = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8).split("\\|");
            if (parts.length != 3 || !parts[0].equals(list)) {
                throw new InvalidQueryException("INVALID_CURSOR");
            }
            return new PageCursor(list, Instant.parse(parts[1]), UUID.fromString(parts[2]));
        } catch (IllegalArgumentException | DateTimeParseException e) {
            throw new InvalidQueryException("INVALID_CURSOR");
        }
    }

    static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new InvalidQueryException("INVALID_LIMIT");
        }
        return requested;
    }
}
