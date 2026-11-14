package com.ecommerceagent.automation.sync;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.ecommerceagent.automation.TaskQueues;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.spring.boot.WorkflowImpl;
import io.temporal.workflow.Workflow;
import org.slf4j.Logger;

@WorkflowImpl(taskQueues = TaskQueues.MAIN)
public class StoreSyncWorkflowImpl implements StoreSyncWorkflow {

    static final int RECENT_IDS = 500;
    static final int CONTINUE_AFTER = 200;

    private static final Logger log = Workflow.getLogger(StoreSyncWorkflowImpl.class);

    private final StoreSyncActivities activities = Workflow.newActivityStub(StoreSyncActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofMinutes(2))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofSeconds(5))
                            .setBackoffCoefficient(2)
                            .setMaximumAttempts(5)
                            .build())
                    .build());

    private final List<CatalogSignal> inbox = new ArrayList<>();
    private final ArrayDeque<String> recent = new ArrayDeque<>();
    private final Set<String> recentSet = new HashSet<>();
    private int refreshed;
    private int handled;

    @Override
    public void run(StoreSyncState state) {
        state.recentEventIds().forEach(this::remember);
        while (true) {
            if (handled >= CONTINUE_AFTER && inbox.isEmpty()) {
                Workflow.continueAsNew(new StoreSyncState(state.connectionId(), List.copyOf(recent),
                        state.refreshDelaySeconds()));
            }
            Workflow.await(() -> !inbox.isEmpty());
            // Collect requests for a moment: several webhooks of one product become one read.
            Workflow.sleep(Duration.ofSeconds(state.refreshDelaySeconds()));
            Set<String> products = new LinkedHashSet<>();
            for (CatalogSignal signal : List.copyOf(inbox)) {
                if (remember(signal.eventId())) {
                    handled++;
                    products.add(signal.productExternalId());
                }
            }
            inbox.clear();
            if (products.isEmpty()) {
                continue;
            }
            try {
                activities.refreshProducts(List.copyOf(products));
                refreshed += products.size();
            } catch (ActivityFailure failure) {
                log.warn("Catalog refresh of {} products failed: {}", products.size(), failure.getMessage());
            }
        }
    }

    @Override
    public void refreshProduct(CatalogSignal signal) {
        inbox.add(signal);
    }

    @Override
    public int refreshed() {
        return refreshed;
    }

    private boolean remember(String eventId) {
        if (!recentSet.add(eventId)) {
            return false;
        }
        recent.addLast(eventId);
        if (recent.size() > RECENT_IDS) {
            recentSet.remove(recent.removeFirst());
        }
        return true;
    }
}
