/**
 * Metrics and Evidence Engine: metric definitions, data quality, freshness, evidence.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Metrics", allowedDependencies = {"commerce", "businessprofile"})
package com.ecommerceagent.metrics;

import org.springframework.modulith.ApplicationModule;
