/**
 * The canonical model types and {@link com.ecommerceagent.intelligence.model.GovernedModelGateway}, the runtime's
 * only way to a paid model (ADR-0016, ADR-0027). Provider adapters live in the internal {@code provider} package,
 * which is not part of this named interface; an architecture test keeps every other class away from it.
 */
@NamedInterface("model")
package com.ecommerceagent.intelligence.model;

import org.springframework.modulith.NamedInterface;
