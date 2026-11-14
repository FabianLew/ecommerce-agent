package com.ecommerceagent.identity;

import java.util.Objects;

/** Verified identity from the OIDC token; (issuer, subject) is the stable key. */
public record UserIdentity(String issuer, String subject, String email, String displayName) {

    public UserIdentity {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
    }
}
