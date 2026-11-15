package com.ecommerceagent.intelligence.model.provider;

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecommerceagent.intelligence.model.ModelGateway;
import com.ecommerceagent.intelligence.model.ModelId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The provider adapters by provider, and the pricing region each one runs in (ADR-0027 section 9). Internal to the
 * model package: only {@link com.ecommerceagent.intelligence.model.GovernedModelGateway} reaches a provider, which
 * the architecture test enforces. A test replaces this bean to run the governed path on a fake provider.
 */
@Component
public class ProviderGateways {

    /** The direct Anthropic API is priced the same everywhere. */
    public static final String GLOBAL_REGION = "global";

    private final Map<String, ModelGateway> byProvider;

    @Autowired
    ProviderGateways(SpringAiAnthropicGateway anthropic) {
        this(Map.of(SpringAiAnthropicGateway.PROVIDER, anthropic));
    }

    public ProviderGateways(Map<String, ModelGateway> byProvider) {
        this.byProvider = Map.copyOf(byProvider);
    }

    public static ProviderGateways of(ModelGateway gateway, String... providers) {
        return new ProviderGateways(Arrays.stream(providers)
                .collect(Collectors.toMap(Function.identity(), provider -> gateway)));
    }

    public ModelGateway forModel(ModelId model) {
        ModelGateway gateway = byProvider.get(model.provider());
        if (gateway == null) {
            throw new IllegalArgumentException("No gateway for provider " + model.provider());
        }
        return gateway;
    }

    /** The region whose price applies to calls of this model; one region per provider adapter for now. */
    public String region(ModelId model) {
        forModel(model);
        return GLOBAL_REGION;
    }
}
