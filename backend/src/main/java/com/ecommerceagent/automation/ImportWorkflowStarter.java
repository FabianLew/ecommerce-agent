package com.ecommerceagent.automation;

import java.util.Optional;
import java.util.UUID;

import com.ecommerceagent.automation.sync.WooImportWorkflow;
import com.ecommerceagent.automation.temporal.StoreContextPropagator;
import com.ecommerceagent.automation.temporal.WorkflowStoreContext;
import com.ecommerceagent.identity.TrustedStoreScope;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.temporal.api.common.v1.WorkflowExecution;
import io.temporal.api.enums.v1.WorkflowExecutionStatus;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.api.workflowservice.v1.DescribeWorkflowExecutionRequest;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import org.springframework.stereotype.Component;

/**
 * Starts and inspects the import workflow of a run (FIX-02). The workflow ID is derived from the run ID and
 * never reused, so a repeated start of the same run (a retry after a lost response, a reconciler pass) can
 * never create a second execution.
 */
@Component
class ImportWorkflowStarter {

    private final WorkflowClient client;
    private final SyncProperties properties;

    ImportWorkflowStarter(WorkflowClient client, SyncProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /**
     * Starts the run's workflow as the given system actor. Returns normally when the workflow runs or has
     * already run; throws when Temporal did not confirm either.
     */
    void start(UUID runId, TrustedStoreScope system, String correlationId) {
        WooImportWorkflow workflow = client.newWorkflowStub(WooImportWorkflow.class, WorkflowOptions.newBuilder()
                .setTaskQueue(TaskQueues.MAIN)
                .setWorkflowId(StoreSync.workflowId(runId))
                .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                .setWorkflowExecutionTimeout(properties.executionTimeout())
                .build());
        try {
            StoreContextPropagator.callWith(WorkflowStoreContext.of(system, correlationId),
                    () -> WorkflowClient.start(workflow::run, runId.toString()));
        } catch (WorkflowExecutionAlreadyStarted alreadyStarted) {
            // An earlier attempt reached Temporal; that execution is the run's only workflow.
        }
    }

    /** Status of the run's workflow, empty if Temporal has no execution with its ID. */
    Optional<WorkflowExecutionStatus> status(UUID runId) {
        DescribeWorkflowExecutionRequest request = DescribeWorkflowExecutionRequest.newBuilder()
                .setNamespace(client.getOptions().getNamespace())
                .setExecution(WorkflowExecution.newBuilder().setWorkflowId(StoreSync.workflowId(runId)))
                .build();
        try {
            return Optional.of(client.getWorkflowServiceStubs().blockingStub().describeWorkflowExecution(request)
                    .getWorkflowExecutionInfo().getStatus());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() == Status.Code.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }
}
