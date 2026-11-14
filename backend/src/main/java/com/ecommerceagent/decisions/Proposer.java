package com.ecommerceagent.decisions;

import java.util.Objects;

/** Who produced a proposal: the deterministic baseline or an agent, with its version. */
public record Proposer(ProposerKind kind, String name, String version) {

    public Proposer {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(version, "version");
    }
}
