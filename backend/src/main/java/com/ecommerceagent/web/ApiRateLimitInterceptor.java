package com.ecommerceagent.web;

import com.ecommerceagent.ApiRateLimits;
import java.io.IOException;
import java.util.Map;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

/**
 * Enforces {@link RateLimited} (ADR-0027 section 6.1): counts the request per user, per store and per endpoint (or
 * per webhook endpoint key) before the handler runs, and answers 429 with {@code Retry-After} over the limits.
 * For an idempotent command with an {@code Idempotency-Key}, a repeat is answered from the stored answer without
 * running the command again, a key reused for another request is refused, and a repeat while the first still runs
 * is answered 409. A failure to count fails the request closed (the handler does not run).
 */
@Component
class ApiRateLimitInterceptor implements HandlerInterceptor {

    static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    static final String CLAIM = ApiRateLimitInterceptor.class.getName() + ".claim";
    static final String BODY = ApiRateLimitInterceptor.class.getName() + ".body";
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,200}");

    private final ApiRequestProtection protection;
    private final ApiRateLimits limits;
    private final ApiErrorWriter errors;
    private final MeterRegistry meters;

    ApiRateLimitInterceptor(ApiRequestProtection protection, ApiRateLimits limits, ApiErrorWriter errors,
            MeterRegistry meters) {
        this.protection = protection;
        this.limits = limits;
        this.errors = errors;
        this.meters = meters;
    }

    record ClaimedKey(String owner, String key) {
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        RateLimited limited = method.getMethodAnnotation(RateLimited.class);
        if (limited == null) {
            return true;
        }
        String endpoint = method.getBeanType().getSimpleName() + "." + method.getMethod().getName();
        Map<String, String> path = pathVariables(request);
        if (limited.value() == RateLimited.Group.WEBHOOK) {
            String key = path.getOrDefault("endpointKey", "-");
            return allowed(request, response, endpoint, "webhook:" + key, limits.webhooksPerEndpointKeyPerMinute());
        }
        String user = user();
        String store = path.getOrDefault("storeId", "-");
        if (!allowed(request, response, endpoint, "work:user:" + user, limits.workPerUserPerMinute())
                || !allowed(request, response, endpoint, "work:store:" + store, limits.workPerStorePerMinute())
                || !allowed(request, response, endpoint, "work:endpoint:" + endpoint,
                        limits.workPerEndpointPerMinute())) {
            return false;
        }
        String key = request.getHeader(IDEMPOTENCY_HEADER);
        if (!limited.idempotent() || key == null) {
            return true;
        }
        if (!KEY.matcher(key).matches()) {
            errors.write(request, response, 400, "INVALID_IDEMPOTENCY_KEY",
                    "The Idempotency-Key must have 8 to 200 letters, digits or . _ : -");
            return false;
        }
        String owner = "user:" + user + ":store:" + store;
        String fingerprint = request.getMethod() + " " + request.getRequestURI();
        switch (protection.claim(owner, key, fingerprint)) {
            case ApiRequestProtection.Claimed claimed -> {
                request.setAttribute(CLAIM, new ClaimedKey(owner, key));
                return true;
            }
            case ApiRequestProtection.Seen seen when seen.status() != null -> {
                meters.counter("operator.api.idempotent.replays", "endpoint", endpoint).increment();
                response.setStatus(seen.status());
                response.setHeader("Idempotent-Replayed", "true");
                if (seen.body() != null) {
                    response.setContentType(seen.contentType());
                    response.getOutputStream().write(seen.body().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return false;
            }
            case ApiRequestProtection.Seen running -> {
                errors.write(request, response, 409, "COMMAND_IN_PROGRESS",
                        "The same command is still running; repeat it later with the same key", true);
                return false;
            }
            case ApiRequestProtection.Conflict conflict -> {
                errors.write(request, response, 422, "IDEMPOTENCY_KEY_REUSED",
                        "This Idempotency-Key was used for a different request");
                return false;
            }
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        if (!(request.getAttribute(CLAIM) instanceof ClaimedKey claimed)) {
            return;
        }
        int status = response.getStatus();
        if (ex != null || status >= 500 || !(request.getAttribute(BODY) instanceof String body) && status != 204
                && status != 202) {
            // No complete answer to store: the key may be used again.
            protection.abandon(claimed.owner(), claimed.key());
            return;
        }
        protection.complete(claimed.owner(), claimed.key(), status, response.getContentType(),
                request.getAttribute(BODY) instanceof String body ? body : null);
    }

    private boolean allowed(HttpServletRequest request, HttpServletResponse response, String endpoint, String bucket,
            int limit) throws IOException {
        if (protection.hit(bucket, limit)) {
            return true;
        }
        meters.counter("operator.api.rate.limited", "endpoint", endpoint).increment();
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(protection.retryAfterSeconds()));
        errors.write(request, response, 429, "RATE_LIMITED", "Too many requests; try again later", true);
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> pathVariables(HttpServletRequest request) {
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> map ? (Map<String, String>) map : Map.of();
    }

    private static String user() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null || authentication.getName() == null ? "anonymous" : authentication.getName();
    }
}
