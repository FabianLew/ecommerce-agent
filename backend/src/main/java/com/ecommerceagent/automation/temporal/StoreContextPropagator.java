package com.ecommerceagent.automation.temporal;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import com.ecommerceagent.Correlation;
import io.temporal.api.common.v1.Payload;
import io.temporal.common.context.ContextPropagator;
import io.temporal.common.converter.DefaultDataConverter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Propagates {@link WorkflowStoreContext} through Temporal headers: client → workflow → activities and
 * child workflows. Also restores {@code correlationId} into the MDC on workflow and activity threads (B-08).
 */
@Component
public class StoreContextPropagator implements ContextPropagator {

    static final String ORGANIZATION_ID = "operator-organization-id";
    static final String STORE_ID = "operator-store-id";
    static final String USER_ID = "operator-user-id";
    static final String CORRELATION_ID = "operator-correlation-id";

    private static final ThreadLocal<WorkflowStoreContext> CURRENT = new ThreadLocal<>();

    /** Runs a workflow client call (start, signal, execute) with the given context in its headers. */
    public static <T> T callWith(WorkflowStoreContext context, Supplier<T> call) {
        WorkflowStoreContext previous = CURRENT.get();
        CURRENT.set(context);
        try {
            return call.get();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    @Override
    public String getName() {
        return "operator-store-context";
    }

    @Override
    public Object getCurrentContext() {
        WorkflowStoreContext context = CURRENT.get();
        if (context != null) {
            return context;
        }
        String correlationId = MDC.get(Correlation.CORRELATION_ID);
        return correlationId == null ? null : new WorkflowStoreContext(null, null, null, correlationId);
    }

    /**
     * Called by Temporal on workflow and activity threads, which are pooled and reused. A missing
     * header (null) must clear the previous execution's context, or it would leak into this one.
     */
    @Override
    public void setCurrentContext(Object context) {
        if (context instanceof WorkflowStoreContext storeContext) {
            CURRENT.set(storeContext);
            if (storeContext.correlationId() != null) {
                MDC.put(Correlation.CORRELATION_ID, storeContext.correlationId());
            } else {
                MDC.remove(Correlation.CORRELATION_ID);
            }
        } else {
            CURRENT.remove();
            MDC.remove(Correlation.CORRELATION_ID);
        }
    }

    @Override
    public Map<String, Payload> serializeContext(Object context) {
        Map<String, Payload> header = new HashMap<>();
        if (context instanceof WorkflowStoreContext c) {
            put(header, ORGANIZATION_ID, c.organizationId());
            put(header, STORE_ID, c.storeId());
            put(header, USER_ID, c.userId());
            put(header, CORRELATION_ID, c.correlationId());
        }
        return header;
    }

    @Override
    public WorkflowStoreContext deserializeContext(Map<String, Payload> header) {
        if (header == null) {
            return null;
        }
        WorkflowStoreContext context = new WorkflowStoreContext(
                uuid(header, ORGANIZATION_ID), uuid(header, STORE_ID), uuid(header, USER_ID),
                string(header, CORRELATION_ID));
        boolean empty = context.organizationId() == null && context.storeId() == null && context.userId() == null
                && context.correlationId() == null;
        return empty ? null : context;
    }

    private static void put(Map<String, Payload> header, String key, Object value) {
        if (value != null) {
            DefaultDataConverter.STANDARD_INSTANCE.toPayload(value.toString()).ifPresent(p -> header.put(key, p));
        }
    }

    private static String string(Map<String, Payload> header, String key) {
        Payload payload = header.get(key);
        return payload == null ? null
                : DefaultDataConverter.STANDARD_INSTANCE.fromPayload(payload, String.class, String.class);
    }

    private static UUID uuid(Map<String, Payload> header, String key) {
        String value = string(header, key);
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
