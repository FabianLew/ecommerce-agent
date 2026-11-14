/**
 * Opportunity, DecisionProposal, decision history and outcomes.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Decisions", allowedDependencies = {"metrics", "identity"})
package com.ecommerceagent.decisions;

import org.springframework.modulith.ApplicationModule;
