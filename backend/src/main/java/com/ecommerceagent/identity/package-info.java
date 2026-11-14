/**
 * Organization, Store, User, store-scoped roles and TrustedStoreScope.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Identity", allowedDependencies = {})
package com.ecommerceagent.identity;

import org.springframework.modulith.ApplicationModule;
