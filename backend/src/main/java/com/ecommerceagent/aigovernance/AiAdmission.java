package com.ecommerceagent.aigovernance;

import java.time.Duration;
import java.util.UUID;

import com.ecommerceagent.identity.TrustedStoreScope;

/**
 * Admission of agent runs (ADR-0027 sections 6.2 and 12). A run holds a lease from {@link #admit} until
 * {@link #release} or until the lease expires; only an admitted run may reserve and send model calls. The limits hold
 * across all application instances: the store limit comes from the agent's run policy, the platform limit from
 * {@code operator.ai-governance.max-concurrent-runs}. While AI is stopped for the platform, the store or the
 * automation, no run is admitted and no reserved call is sent ({@link SendDecision#STOPPED},
 * {@link SendDecision#STORE_STOPPED}, {@link SendDecision#AUTOMATION_STOPPED}).
 */
public interface AiAdmission {

    Admission admit(TrustedStoreScope scope, UUID agentRunId, String automationType, int maxStoreRuns, Duration ttl);

    /** Ends the run's lease; releasing twice, or an expired lease, changes nothing. */
    void release(TrustedStoreScope scope, UUID agentRunId);

    /** Whether AI is stopped for the platform now. */
    boolean stopped();

    enum Admission {
        GRANTED,
        /** The platform AI stop is on. */
        AI_STOPPED,
        /** AI is stopped for this store (ADR-0027 section 12; independent of the platform stop). */
        STORE_STOPPED,
        /** AI is stopped for this automation, on every store or on this one. */
        AUTOMATION_STOPPED,
        /** The store already has as many active runs as its limit allows. */
        STORE_LIMIT,
        /** The platform already has as many active runs as its limit allows. */
        GLOBAL_LIMIT,
        /** The run was admitted before and its lease ended: a run is admitted once. */
        LEASE_ENDED,
        /** The automation is not registered in {@code ai_automation_type} (fail closed). */
        UNKNOWN_AUTOMATION;

        public boolean granted() {
            return this == GRANTED;
        }
    }
}
