package com.ecommerceagent.web;

import java.io.IOException;
import java.util.UUID;

import com.ecommerceagent.Correlation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns {@code requestId} and {@code correlationId} to every request (B-07), before security, so
 * that 401 responses carry them too. Values go to the MDC (structured logs), the response headers and
 * error bodies. Logs one access line per request without the query string (no PII in logs).
 */
class RequestCorrelationFilter extends OncePerRequestFilter {

    static final String REQUEST_ID_ATTRIBUTE = RequestCorrelationFilter.class.getName() + ".requestId";

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("com.ecommerceagent.web.access");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = incomingOrNew(request.getHeader(Correlation.REQUEST_ID_HEADER));
        String incomingCorrelation = request.getHeader(Correlation.CORRELATION_ID_HEADER);
        String correlationId = Correlation.isSafeId(incomingCorrelation) ? incomingCorrelation : requestId;

        request.setAttribute(REQUEST_ID_ATTRIBUTE, requestId);
        response.setHeader(Correlation.REQUEST_ID_HEADER, requestId);
        response.setHeader(Correlation.CORRELATION_ID_HEADER, correlationId);
        MDC.put(Correlation.REQUEST_ID, requestId);
        MDC.put(Correlation.CORRELATION_ID, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            ACCESS_LOG.info("{} {} -> {} in {} ms", request.getMethod(), request.getRequestURI(),
                    response.getStatus(), (System.nanoTime() - started) / 1_000_000);
            MDC.remove(Correlation.REQUEST_ID);
            MDC.remove(Correlation.CORRELATION_ID);
        }
    }

    private static String incomingOrNew(String incoming) {
        return Correlation.isSafeId(incoming) ? incoming : UUID.randomUUID().toString();
    }

    static String requestId(HttpServletRequest request) {
        Object value = request.getAttribute(REQUEST_ID_ATTRIBUTE);
        return value == null ? null : value.toString();
    }
}
