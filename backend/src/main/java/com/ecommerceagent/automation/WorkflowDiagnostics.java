package com.ecommerceagent.automation;

import java.time.Duration;
import java.util.UUID;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.automation.diagnostics.ScopeCheckWorkflow;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/** Runs the scope diagnostic workflow for a verified store scope and waits for its result. */
@Service
public class WorkflowDiagnostics {

    private final WorkflowClient client;

    WorkflowDiagnostics(WorkflowClient client) {
        this.client = client;
    }

    public ScopeCheckResult checkStoreScope(TrustedStoreScope scope) {
        ScopeCheckWorkflow workflow = client.newWorkflowStub(ScopeCheckWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId("scope-check-" + scope.storeId() + "-" + UUID.randomUUID())
                .setWorkflowExecutionTimeout(Duration.ofMinutes(1))
                .build());
        WorkflowStoreContext context = new WorkflowStoreContext(scope.organizationId(), scope.storeId(),
                scope.userId(), MDC.get(Correlation.CORRELATION_ID));
        return StoreContextPropagator.callWith(context, workflow::check);
    }
}
