package com.ecommerceagent.automation.sync;

import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.integration.IntegrationNotFoundException;
import com.ecommerceagent.integration.InvalidIntegrationRequestException;
import com.ecommerceagent.integration.SyncRunStatus;
import com.ecommerceagent.integration.WooCommerceImport;
import com.ecommerceagent.integration.WooImportException;
import io.temporal.activity.Activity;
import io.temporal.failure.ApplicationFailure;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class WooImportActivitiesImpl implements WooImportActivities {

    private final WooCommerceImport woo;

    WooImportActivitiesImpl(WooCommerceImport woo) {
        this.woo = woo;
    }

    @Override
    public void importCatalog(String runId) {
        guarded(() -> woo.importCatalog(ActivityStoreScope.current(), UUID.fromString(runId), WooImportActivitiesImpl::heartbeat));
    }

    @Override
    public boolean importOrders(String runId) {
        return guarded(() -> woo.importOrders(ActivityStoreScope.current(), UUID.fromString(runId),
                WooImportActivitiesImpl::heartbeat).complete());
    }

    @Override
    public void finish(String runId, String status, String errorCode) {
        woo.finish(ActivityStoreScope.current(), UUID.fromString(runId), SyncRunStatus.valueOf(status), errorCode);
    }

    private static void heartbeat() {
        Activity.getExecutionContext().heartbeat(null);
    }

    /** Store-side errors keep their code; errors no retry can fix are not retried. */
    private static <T> T guarded(java.util.function.Supplier<T> step) {
        try {
            return step.get();
        } catch (WooImportException e) {
            if (e.permanent()) {
                throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.error().name());
            }
            throw ApplicationFailure.newFailure(e.getMessage(), e.error().name());
        } catch (InvalidIntegrationRequestException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), e.code());
        } catch (IntegrationNotFoundException e) {
            throw ApplicationFailure.newNonRetryableFailure(e.getMessage(), "NOT_FOUND");
        }
    }
}
