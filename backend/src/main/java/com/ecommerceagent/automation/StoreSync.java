package com.ecommerceagent.automation;

import java.util.UUID;

import com.ecommerceagent.Correlation;
import com.ecommerceagent.identity.StoreAccess;
import com.ecommerceagent.identity.StorePermission;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.SyncRun;
import com.ecommerceagent.integration.WooCommerceImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

/**
 * Starts store imports (B-13, ADR-0008). A user with {@code STORE_MANAGE} requests the import; the workflow
 * runs as the system actor {@value #IMPORT_SERVICE}, which only reads from the store and writes its source data.
 *
 * <p>The {@code RUNNING} run record is the durable request to start the workflow (FIX-02). This class tries
 * to start it right away; if the start fails or its outcome is unknown (a lost response, a crash), the run
 * stays unconfirmed and {@link SyncRunReconciler} starts it with the same workflow ID or closes it. A failed
 * start is never taken as proof that no workflow runs.
 */
@Service
public class StoreSync {

    public static final String IMPORT_SERVICE = "woo-import";

    private static final Logger log = LoggerFactory.getLogger(StoreSync.class);

    private final StoreAccess storeAccess;
    private final WooCommerceImport woo;
    private final ImportWorkflowStarter starter;

    StoreSync(StoreAccess storeAccess, WooCommerceImport woo, ImportWorkflowStarter starter) {
        this.storeAccess = storeAccess;
        this.woo = woo;
        this.starter = starter;
    }

    public SyncRun startInitialImport(TrustedStoreScope scope, UUID connectionId) {
        TrustedStoreScope requester = storeAccess.reauthorize(scope, StorePermission.STORE_MANAGE);
        SyncRun run = woo.createInitialImport(requester, connectionId);
        TrustedStoreScope system = importScope(requester.organizationId(), requester.storeId());
        try {
            starter.start(run.id(), system, MDC.get(Correlation.CORRELATION_ID));
            woo.markWorkflowStarted(system, run.id());
        } catch (RuntimeException e) {
            log.warn("Import workflow start not confirmed for run {}; the reconciler will retry", run.id(), e);
        }
        return run;
    }

    TrustedStoreScope importScope(UUID organizationId, UUID storeId) {
        return storeAccess.systemScope(IMPORT_SERVICE, organizationId, storeId).orElseThrow();
    }

    public static String workflowId(UUID runId) {
        return "woo-import-" + runId;
    }
}
