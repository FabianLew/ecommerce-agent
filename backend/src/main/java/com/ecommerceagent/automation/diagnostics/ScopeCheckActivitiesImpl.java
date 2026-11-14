package com.ecommerceagent.automation.diagnostics;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.automation.ScopeCheckResult;
import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.identity.Actor.SystemActor;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class ScopeCheckActivitiesImpl implements ScopeCheckActivities {

    @Override
    public ScopeCheckResult verifiedScope() {
        TrustedStoreScope scope = ActivityStoreScope.current();
        return new ScopeCheckResult(
                scope.organizationId().toString(),
                scope.storeId().toString(),
                scope.actor().kind(),
                scope.userId().map(Object::toString).orElse(null),
                scope.actor() instanceof SystemActor system ? system.service() : null,
                scope.roles().stream().map(Enum::name).sorted().toList(),
                scope.permissions().stream().map(Enum::name).toList(),
                MDC.get(Correlation.CORRELATION_ID),
                Activity.getExecutionContext().getInfo().getWorkflowId());
    }
}
