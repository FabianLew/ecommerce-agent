package com.ecommerceagent.automation;

import java.time.Duration;
import java.util.UUID;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.automation.sync.WooImportWorkflow;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.SyncRun;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Starts store imports (B-13, ADR-0008). A user with {@code STORE_MANAGE} requests the import; the workflow
 * runs as the system actor {@value #IMPORT_SERVICE}, which only reads from the store and writes its source data.
 */
@Service
public class StoreSync {

    public static final String IMPORT_SERVICE = "woo-import";

    private final StoreAccess storeAccess;
    private final WooCommerceImport woo;
    private final WorkflowClient client;

    StoreSync(StoreAccess storeAccess, WooCommerceImport woo, WorkflowClient client) {
        this.storeAccess = storeAccess;
        this.woo = woo;
        this.client = client;
    }

    public SyncRun startInitialImport(TrustedStoreScope scope, UUID connectionId) {
        TrustedStoreScope requester = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        SyncRun run = woo.createInitialImport(requester, connectionId);
        TrustedStoreScope system = storeAccess
                .systemScope(IMPORT_SERVICE, requester.organizationId(), requester.storeId())
                .orElseThrow();
        WooImportWorkflow workflow = client.newWorkflowStub(WooImportWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId(workflowId(run.id()))
                .setWorkflowExecutionTimeout(Duration.ofHours(2))
                .build());
        try {
            StoreContextPropagator.callWith(WorkflowStoreContext.of(system, MDC.get(Correlation.CORRELATION_ID)),
                    () -> WorkflowClient.start(workflow::run, run.id().toString()));
        } catch (RuntimeException e) {
            woo.finish(system, run.id(), SyncRunStatus.FAILED, "START_FAILED");
            throw e;
        }
        return run;
    }

    public static String workflowId(UUID runId) {
        return "woo-import-" + runId;
    }
}
