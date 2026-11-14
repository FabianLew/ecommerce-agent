package com.ecommerceagent.web;

/** Carries no identifiers on purpose: a 404 must not reveal what exists (document 04 §3). */
class ResourceNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    ResourceNotFoundException() {
        super("Resource not found", null, false, false);
    }
}
