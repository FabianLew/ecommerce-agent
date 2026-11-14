package com.ecommerceagent.automation.temporal;

import java.util.Map;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.identity.AuditEntry;
import com.ecommerceagent.identity.AuditTrail;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptor;
import io.temporal.common.interceptors.ActivityInboundCallsInterceptorBase;
import io.temporal.common.interceptors.WorkerInterceptorBase;
import io.temporal.failure.ApplicationFailure;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Re-verifies the store claim from the Temporal header against memberships before every activity and
 * exposes the result as {@link ActivityStoreScope}. A missing or unverifiable claim fails the activity
 * with a non-retryable error: Temporal headers are not trusted on their own (ADR-0004).
 */
@Component
public class StoreScopeActivityInterceptor extends WorkerInterceptorBase {

    public static final String SCOPE_MISSING = "STORE_SCOPE_MISSING";
    public static final String SCOPE_REJECTED = "STORE_SCOPE_REJECTED";

    private final StoreContextPropagator propagator;
    private final StoreAccess storeAccess;
    private final AuditTrail audit;

    StoreScopeActivityInterceptor(StoreContextPropagator propagator, StoreAccess storeAccess, AuditTrail audit) {
        this.propagator = propagator;
        this.storeAccess = storeAccess;
        this.audit = audit;
    }

    @Override
    public ActivityInboundCallsInterceptor interceptActivity(ActivityInboundCallsInterceptor next) {
        return new ActivityInboundCallsInterceptorBase(next) {
            @Override
            public ActivityOutput execute(ActivityInput input) {
                WorkflowStoreContext context = propagator.deserializeContext(input.getHeader().getValues());
                TrustedStoreScope scope = verify(context);
                ActivityStoreScope.set(scope);
                MDC.put(Correlation.CORRELATION_ID, context.correlationId());
                MDC.put("storeId", scope.storeId().toString());
                try {
                    return super.execute(input);
                } finally {
                    ActivityStoreScope.clear();
                    MDC.remove(Correlation.CORRELATION_ID);
                    MDC.remove("storeId");
                }
            }
        };
    }

    private TrustedStoreScope verify(WorkflowStoreContext context) {
        if (context == null || !context.hasStoreClaim()) {
            throw ApplicationFailure.newNonRetryableFailure("Activity started without a store scope", SCOPE_MISSING);
        }
        return storeAccess.scopeFor(context.userId(), context.storeId())
                .filter(scope -> scope.organizationId().equals(context.organizationId()))
                .orElseThrow(() -> {
                    audit.record(new AuditEntry("STORE_ACCESS_DENIED", null, null, context.userId(), "store",
                            context.storeId().toString(), Map.of("channel", "temporal-activity")));
                    return ApplicationFailure.newNonRetryableFailure(
                            "Store scope could not be verified", SCOPE_REJECTED);
                });
    }
}
