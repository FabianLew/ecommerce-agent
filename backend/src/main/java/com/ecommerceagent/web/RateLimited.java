package com.ecommerceagent.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * An endpoint that starts background work, a check against an external system or an analysis (ADR-0027 section
 * 6.1): counted per user, per store and per endpoint, and answered 429 over the limits of
 * {@link com.ecommerceagent.ApiRateLimits}. With {@code idempotent}, a command sent with an {@code Idempotency-Key} header is answered
 * once and a repeat gets the stored answer. Safety actions (stopping mutations, revoking a mapping) are never
 * limited.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@interface RateLimited {

    Group value();

    boolean idempotent() default false;

    enum Group {
        /** Requests of signed-in users that start work. */
        WORK,
        /** Webhook deliveries, counted per endpoint key. */
        WEBHOOK
    }
}
