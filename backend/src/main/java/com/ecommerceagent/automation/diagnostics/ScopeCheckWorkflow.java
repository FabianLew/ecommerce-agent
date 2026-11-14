package com.ecommerceagent.automation.diagnostics;

import com.ecommerceagent.automation.ScopeCheckResult;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Diagnostic workflow (B-08): proves that store scope and correlation reach a verified activity. */
@WorkflowInterface
public interface ScopeCheckWorkflow {

    @WorkflowMethod
    ScopeCheckResult check();
}
