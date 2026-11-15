/**
 * Read models for dashboard, analytics and activity.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Reporting", allowedDependencies = {"metrics", "decisions", "commerce", "identity"})
package com.ecommerceagent.reporting;

import org.springframework.modulith.ApplicationModule;
