package com.ecommerceagent.automation.guardian;

import java.util.UUID;

import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * One Inventory Guardian episode, ID {@code inventory-guardian-{storeId}-{variantId}-{opportunityId}} (ADR-0011,
 * ADR-0012): context, proposal, validation and policy evaluation, then the episode status the outcome implies. In
 * shadow mode nothing is executed and the workflow does not wait for approval.
 */
@WorkflowInterface
public interface InventoryGuardianWorkflow {

    @WorkflowMethod
    void run(GuardianInput input);

    /** The risk of the variant changed; a significant change starts a new revision. */
    @SignalMethod
    void riskUpdated(RiskSignal signal);

    /** The variant is no longer at risk: the episode is resolved. */
    @SignalMethod
    void riskResolved(RiskSignal signal);

    @QueryMethod
    GuardianStatus status();

    static String workflowId(UUID storeId, String variantId, String opportunityId) {
        return "inventory-guardian-" + storeId + "-" + variantId + "-" + opportunityId;
    }
}
