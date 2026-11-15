package com.ecommerceagent.automation.execution;

import java.util.Optional;

import com.ecommerceagent.identity.TrustedStoreScope;
import org.springframework.modulith.NamedInterface;
import org.springframework.stereotype.Service;

/**
 * Whether external mutations may run (ADR-0020 point 7). Checked when approving, authorizing and right before a
 * write; never for reads, verification or reconciliation.
 */
@Service
@NamedInterface("execution")
public class MutationStops {

    public static final String GLOBAL = "MUTATIONS_STOPPED_GLOBALLY";

    private final ExecutionSettings settings;

    MutationStops(ExecutionSettings settings) {
        this.settings = settings;
    }

    /** The reason mutations are stopped for the store, or empty when they may run. */
    public Optional<String> blocker(TrustedStoreScope scope) {
        return settings.mutationsEnabled() ? Optional.empty() : Optional.of(GLOBAL);
    }
}
