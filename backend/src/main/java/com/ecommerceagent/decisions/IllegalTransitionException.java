package com.ecommerceagent.decisions;

/** A status change that the state machine does not allow, or that lost a race with another change. */
public class IllegalTransitionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public IllegalTransitionException(String message) {
        super(message);
    }
}
