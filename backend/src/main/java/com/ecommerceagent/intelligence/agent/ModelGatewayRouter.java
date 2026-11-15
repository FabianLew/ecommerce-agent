package com.ecommerceagent.intelligence.agent;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.ecommerceagent.intelligence.model.ModelGateway;
import com.ecommerceagent.intelligence.model.ModelId;
import com.ecommerceagent.intelligence.model.SpringAiAnthropicGateway;
import org.springframework.stereotype.Component;

/** Picks the gateway of a model's provider. A test replaces it to run the runtime on a fake gateway. */
@Component
public class ModelGatewayRouter {

    private final Map<String, ModelGateway> byProvider;

    @org.springframework.beans.factory.annotation.Autowired
    ModelGatewayRouter(SpringAiAnthropicGateway anthropic) {
        this(Map.of(SpringAiAnthropicGateway.PROVIDER, anthropic));
    }

    public ModelGatewayRouter(Map<String, ModelGateway> byProvider) {
        this.byProvider = Map.copyOf(byProvider);
    }

    public static ModelGatewayRouter of(ModelGateway gateway, String... providers) {
        return new ModelGatewayRouter(java.util.Arrays.stream(providers)
                .collect(Collectors.toMap(Function.identity(), provider -> gateway)));
    }

    public ModelGateway forModel(ModelId model) {
        ModelGateway gateway = byProvider.get(model.provider());
        if (gateway == null) {
            throw new IllegalArgumentException("No gateway for provider " + model.provider());
        }
        return gateway;
    }
}
