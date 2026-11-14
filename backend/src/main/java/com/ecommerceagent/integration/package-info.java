/**
 * WooCommerce, Meta and mail adapters; inbox, normalization, integration health, secrets.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Integration", allowedDependencies = {"commerce", "identity"})
package com.ecommerceagent.integration;

import org.springframework.modulith.ApplicationModule;
