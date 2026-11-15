package com.ecommerceagent.identity;

/** The user is not, or is no longer, a platform operator. */
public class PlatformOperatorDeniedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PlatformOperatorDeniedException() {
        super("Not a platform operator");
    }
}
