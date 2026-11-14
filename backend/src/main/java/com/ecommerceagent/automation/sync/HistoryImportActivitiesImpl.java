package com.ecommerceagent.automation.sync;

import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.integration.WooHistoryImport;
import io.temporal.activity.Activity;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class HistoryImportActivitiesImpl implements HistoryImportActivities {

    private final WooHistoryImport history;

    HistoryImportActivitiesImpl(WooHistoryImport history) {
        this.history = history;
    }

    @Override
    public String importNext(String connectionId) {
        return ActivityErrors.guarded(() -> history.importNext(ActivityStoreScope.current(),
                UUID.fromString(connectionId), () -> Activity.getExecutionContext().heartbeat(null)).outcome().name());
    }
}
