package com.ecommerceagent.intelligence.agent;

import java.util.List;

import com.ecommerceagent.intelligence.model.ModelId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/**
 * {@code operator.agent} (ADR-0016 point 9): whether the agent proposes, its models and effort. The limits of a run
 * are the agent's versioned run policy, and every price comes from the catalog {@code ai_price} (ADR-0027 section 9),
 * not from settings here.
 */
@ConfigurationProperties("operator.agent")
public record AgentSettings(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<String> models,
        String effort) {

    public AgentSettings {
        models = List.copyOf(models);
    }

    public List<ModelId> modelIds() {
        return models.stream().map(ModelId::parse).toList();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentSettings.class)
    static class Registration {
    }
}
