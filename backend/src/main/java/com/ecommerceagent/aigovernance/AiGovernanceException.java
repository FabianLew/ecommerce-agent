package com.ecommerceagent.aigovernance;

/** Governance could not decide. Callers treat it as a denial: no paid call is sent (fail closed). */
public class AiGovernanceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public AiGovernanceException(String message) {
        super(message);
    }

    public AiGovernanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
