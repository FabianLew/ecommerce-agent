/**
 * Catalog, variants, orders, stock, ad model and SKU-to-ad mappings.
 *
 * <p>Allowed dependencies follow the module map in the architecture plan (B2); dependencies point
 * down the map only, and upward communication uses domain events.
 */
@ApplicationModule(displayName = "Commerce", allowedDependencies = {"identity"})
package com.ecommerceagent.commerce;

import org.springframework.modulith.ApplicationModule;
