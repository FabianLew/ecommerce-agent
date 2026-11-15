/**
 * Policy engine, approvals, AuthorizedAction, action executor with ledger, Temporal workflows.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Automation", allowedDependencies = {"decisions", "intelligence", "intelligence :: inventory", "integration", "metrics", "commerce", "businessprofile", "identity"})
package com.ecommerceagent.automation;

import org.springframework.modulith.ApplicationModule;
