/**
 * AI cost governance (ADR-0027): the versioned price catalog, budgets and atomic cost reservations. Every paid model
 * call is admitted by {@link com.ecommerceagent.aigovernance.AiBudgetGovernor} before it is sent.
 *
 * <p>Budgets above a store (platform, organization) are not store data: the module reaches them only through the
 * reviewed database functions {@code ai_reserve} and {@code ai_release}, which take the store from the bound scope.
 */
@ApplicationModule(displayName = "AI governance", allowedDependencies = {"identity"})
package com.ecommerceagent.aigovernance;

import org.springframework.modulith.ApplicationModule;
