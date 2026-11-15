package com.ecommerceagent.automation.inventory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import com.ecommerceagent.automation.TaskQueues;
import com.ecommerceagent.automation.temporal.ActivityErrors;
import com.ecommerceagent.automation.guardian.GuardianSettings;
import com.ecommerceagent.automation.guardian.InventoryGuardianWorkflow;
import com.ecommerceagent.automation.guardian.RiskSignal;
import com.ecommerceagent.automation.temporal.ActivityStoreScope;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.decisions.InventoryRiskDetector;
import com.ecommerceagent.decisions.Opportunity;
import com.ecommerceagent.decisions.RiskAssessment;
import com.ecommerceagent.identity.TrustedStoreScope;
import com.ecommerceagent.integration.WooSourceRefresh;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.BatchRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowNotFoundException;
import io.temporal.client.WorkflowOptions;
import io.temporal.spring.boot.ActivityImpl;
import org.springframework.stereotype.Component;

@Component
@ActivityImpl(taskQueues = TaskQueues.MAIN)
public class VariantInventoryActivitiesImpl implements VariantInventoryActivities {

    private final WooSourceRefresh refresh;
    private final InventoryRiskDetector detector;
    private final Clock clock;
    private final WorkflowClient client;
    private final GuardianSettings guardianSettings;
    private final StockWarnings stockWarnings;

    VariantInventoryActivitiesImpl(WooSourceRefresh refresh, InventoryRiskDetector detector, Clock clock,
            WorkflowClient client, GuardianSettings guardianSettings, StockWarnings stockWarnings) {
        this.refresh = refresh;
        this.detector = detector;
        this.clock = clock;
        this.client = client;
        this.guardianSettings = guardianSettings;
        this.stockWarnings = stockWarnings;
    }

    @Override
    public String verify(String variantId) {
        return ActivityErrors.guarded(() -> refresh.refreshVariant(ActivityStoreScope.current(),
                UUID.fromString(variantId)));
    }

    @Override
    public RiskCheck assessRisk(String variantId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        return check(scope, detector.assess(scope, UUID.fromString(variantId), null));
    }

    @Override
    public RiskCheck verifyAndAssess(String variantId) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        UUID id = UUID.fromString(variantId);
        Instant readAt = clock.instant();
        String outcome = ActivityErrors.guarded(() -> refresh.refreshVariant(scope, id));
        // A read that recorded the stock, or found it unchanged, confirms it as of the read.
        Instant confirmedAt = confirms(outcome) ? readAt : null;
        return check(scope, detector.assess(scope, id, confirmedAt));
    }

    @Override
    public void notifyGuardian(String variantId, RiskCheck check) {
        TrustedStoreScope scope = ActivityStoreScope.current();
        String workflowId = InventoryGuardianWorkflow.workflowId(scope.storeId(), variantId, check.opportunityId());
        RiskSignal signal = new RiskSignal(check.outcome(), check.daysOfCover());
        WorkflowStoreContext context = WorkflowStoreContext.of(scope, "guardian-" + check.opportunityId());
        if ("NOT_AT_RISK".equals(check.outcome())) {
            try {
                StoreContextPropagator.callWith(context, () -> {
                    client.newWorkflowStub(InventoryGuardianWorkflow.class, workflowId).riskResolved(signal);
                    return null;
                });
            } catch (WorkflowNotFoundException e) {
                // The episode's workflow already finished.
            }
            return;
        }
        InventoryGuardianWorkflow guardian = client.newWorkflowStub(InventoryGuardianWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(TaskQueues.MAIN)
                        .setWorkflowId(workflowId)
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build());
        BatchRequest batch = client.newSignalWithStartRequest();
        batch.add(guardian::run, guardianSettings.input(check.opportunityId(), variantId));
        batch.add(guardian::riskUpdated, signal);
        try {
            StoreContextPropagator.callWith(context, () -> client.signalWithStart(batch));
        } catch (WorkflowExecutionAlreadyStarted e) {
            // The episode's workflow ran and finished; the same id never starts a second one.
        }
    }

    static boolean confirms(String outcome) {
        return outcome != null && !outcome.isEmpty() && Arrays.stream(outcome.split(","))
                .allMatch(part -> part.equals("RECORDED") || part.equals("DUPLICATE"));
    }

    /** After every observation: the L0 warning of the day (ADR-0026 point 1), then the result for the workflow. */
    private RiskCheck check(TrustedStoreScope scope, RiskAssessment assessment) {
        stockWarnings.warnIfLow(scope, assessment.observation());
        Long next = assessment.nextCheckAt() == null ? null
                : Math.max(1, Duration.between(clock.instant(), assessment.nextCheckAt()).toSeconds());
        Opportunity opportunity = assessment.opportunity();
        return new RiskCheck(assessment.observation().outcome().name(), assessment.observation().reason(),
                opportunity == null ? null : opportunity.id().toString(), assessment.opened(), next,
                assessment.observation().daysOfCover() == null ? null
                        : assessment.observation().daysOfCover().toPlainString());
    }
}
