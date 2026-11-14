package com.ecommerceagent.web;

import com.ecommerceagent.automation.ScopeCheckResult;
import com.ecommerceagent.automation.WorkflowDiagnostics;
import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Local-only diagnostic (B-08): runs a workflow on the worker and returns the scope its activity
 * verified. Not available outside the "local" profile.
 */
@Profile("local")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/diagnostics")
class DiagnosticsController {

    private final WorkflowDiagnostics diagnostics;

    DiagnosticsController(WorkflowDiagnostics diagnostics) {
        this.diagnostics = diagnostics;
    }

    @PostMapping("/workflow-scope-check")
    ScopeCheckResult workflowScopeCheck(TrustedStoreScope scope) {
        return diagnostics.checkStoreScope(scope);
    }
}
