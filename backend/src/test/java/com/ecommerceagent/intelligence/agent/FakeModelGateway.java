package com.ecommerceagent.intelligence.agent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

import com.ecommerceagent.intelligence.model.ModelCapabilities;
import com.ecommerceagent.intelligence.model.ModelFailure;
import com.ecommerceagent.intelligence.model.ModelGateway;
import com.ecommerceagent.intelligence.model.ModelId;
import com.ecommerceagent.intelligence.model.ModelRequest;
import com.ecommerceagent.intelligence.model.ModelTurn;
import com.ecommerceagent.intelligence.model.ModelUsage;
import com.ecommerceagent.intelligence.model.ToolCall;

/**
 * A scripted model for runtime safety tests (B-36): each call takes the next step, which may look at the request.
 * It records every request and never runs a tool.
 */
public final class FakeModelGateway implements ModelGateway {

    private final Deque<Function<ModelRequest, ModelTurn>> script = new ArrayDeque<>();
    private final List<ModelRequest> requests = new ArrayList<>();

    public synchronized FakeModelGateway then(Function<ModelRequest, ModelTurn> step) {
        script.add(step);
        return this;
    }

    public FakeModelGateway thenTools(ToolCall... calls) {
        return then(request -> new ModelTurn("", List.of(calls), ModelTurn.FinishReason.TOOL_USE, usage(), "req-tools",
                null));
    }

    public FakeModelGateway thenText(String text) {
        return then(request -> text(text));
    }

    public FakeModelGateway thenTruncated(String text) {
        return then(request -> new ModelTurn(text, List.of(), ModelTurn.FinishReason.MAX_TOKENS, usage(), "req-cut",
                null));
    }

    public FakeModelGateway thenFailure(ModelFailure.Type type) {
        return thenFailure(type, false);
    }

    /** {@code confirmedNotCharged}: an error response the provider does not bill, as an adapter would report it. */
    public FakeModelGateway thenFailure(ModelFailure.Type type, boolean confirmedNotCharged) {
        return then(request -> {
            throw new ModelFailure(type, "scripted", null, "req-failed", confirmedNotCharged, null);
        });
    }

    public static ModelTurn text(String text) {
        return new ModelTurn(text, List.of(), ModelTurn.FinishReason.COMPLETE, usage(), "req-text", null);
    }

    public static ModelUsage usage() {
        return new ModelUsage(500, 100);
    }

    public synchronized void reset() {
        script.clear();
        requests.clear();
    }

    public synchronized List<ModelRequest> requests() {
        return List.copyOf(requests);
    }

    @Override
    public ModelCapabilities capabilities(ModelId model) {
        return new ModelCapabilities(true, false, 200_000, 8_000);
    }

    @Override
    public ModelTurn complete(ModelRequest request) {
        Function<ModelRequest, ModelTurn> step;
        synchronized (this) {
            requests.add(request);
            step = script.poll();
        }
        if (step == null) {
            throw new ModelFailure(ModelFailure.Type.UNAVAILABLE, "script exhausted", null, null, null);
        }
        return step.apply(request);
    }
}
