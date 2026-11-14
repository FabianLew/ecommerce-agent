package com.ecommerceagent.automation.diagnostics;

import com.ecommerceagent.automation.ScopeCheckResult;
import io.temporal.activity.ActivityInterface;

@ActivityInterface
public interface ScopeCheckActivities {

    ScopeCheckResult verifiedScope();
}
