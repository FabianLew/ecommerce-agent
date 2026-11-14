/**
 * Store business profile, costs and their versions.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Business Profile", allowedDependencies = {"identity"})
package com.ecommerceagent.businessprofile;

import org.springframework.modulith.ApplicationModule;
