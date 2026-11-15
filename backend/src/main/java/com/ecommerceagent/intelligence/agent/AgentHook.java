package com.ecommerceagent.intelligence.agent;

import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ModelTurn;
import com.ecommerceagent.intelligence.model.ToolCall;

/**
 * Deterministic checks around a run (document 02 §8). A hook stops a step by throwing {@link HookVeto}; it never
 * changes what the model asked for. Limits and the allowlist are enforced by the runtime and the dispatcher
 * themselves, so a hook adds controls and cannot remove them.
 */
public interface AgentHook {

    default void beforeAgentRun(AgentRunContext run) {
    }

    default void beforeModelCall(AgentRunContext run, ModelRequest request) {
    }

    default void afterModelCall(AgentRunContext run, ModelTurn turn) {
    }

    default void beforeToolCall(AgentRunContext run, ToolCall call) {
    }

    default void afterToolCall(AgentRunContext run, ToolCall call, ToolOutcome outcome) {
    }

    /** Stops the step; {@code code} becomes the run's stop reason or the tool error message. */
    final class HookVeto extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public HookVeto(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
