/**
 * Agent Runtime, ModelGateway, knowledge registry, context builder, read tools, output validation.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Intelligence", allowedDependencies = {"decisions", "metrics", "commerce", "businessprofile", "support", "identity", "aigovernance"})
package com.ecommerceagent.intelligence;

import org.springframework.modulith.ApplicationModule;
