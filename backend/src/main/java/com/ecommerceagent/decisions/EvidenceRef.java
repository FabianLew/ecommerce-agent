package com.ecommerceagent.decisions;

import java.util.Objects;
import java.util.UUID;

public record EvidenceRef(UUID evidenceId, int version) {

    public EvidenceRef {
        Objects.requireNonNull(evidenceId, "evidenceId");
    }
}
