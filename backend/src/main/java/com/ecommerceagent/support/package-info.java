/**
 * Conversations, messages, support cases, drafts and ownership.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Support", allowedDependencies = {"commerce", "identity"})
package com.ecommerceagent.support;

import org.springframework.modulith.ApplicationModule;
