/**
 * REST API: controllers, DTOs, command idempotency, ETags.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Web", allowedDependencies = {"identity", "integration", "commerce", "businessprofile", "metrics", "decisions", "support", "reporting", "intelligence", "automation"})
package com.ecommerceagent.web;

import org.springframework.modulith.ApplicationModule;
