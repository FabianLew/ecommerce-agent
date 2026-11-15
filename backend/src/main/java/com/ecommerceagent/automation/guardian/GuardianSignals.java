package com.ecommerceagent.automation.guardian;

import java.util.UUID;

import com.ecommerceagent.decisions.Opportunity;
import io.temporal.client.WorkflowClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Signals from the API to an episode's workflow. A lost signal is caught up by the workflow's own lookup. */
@Component
public class GuardianSignals {

    private static final Logger log = LoggerFactory.getLogger(GuardianSignals.class);

    private final WorkflowClient workflows;

    GuardianSignals(WorkflowClient workflows) {
        this.workflows = workflows;
    }

    public void approvalDecided(UUID storeId, Opportunity opportunity, UUID approvalId) {
        String workflowId = InventoryGuardianWorkflow.workflowId(storeId, opportunity.subjectId().toString(),
                opportunity.id().toString());
        try {
            workflows.newWorkflowStub(InventoryGuardianWorkflow.class, workflowId)
                    .approvalDecided(approvalId.toString());
        } catch (RuntimeException e) {
            log.warn("Approval {} could not be signalled to {}: {}", approvalId, workflowId, e.toString());
        }
    }
}
