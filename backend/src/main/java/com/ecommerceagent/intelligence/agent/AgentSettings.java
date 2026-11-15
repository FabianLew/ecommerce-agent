package com.ecommerceagent.intelligence.agent;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import com.ecommerceagent.intelligence.model.ModelId;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Configuration;

/** {@code operator.agent} (ADR-0016 point 9): whether the agent proposes, its models, effort and price list. */
@ConfigurationProperties("operator.agent")
public record AgentSettings(
        @DefaultValue("false") boolean enabled,
        @DefaultValue List<String> models,
        String effort,
        @DefaultValue("1500") int maxOutputTokensPerCall,
        @DefaultValue PriceListSettings priceList) {

    public AgentSettings {
        models = List.copyOf(models);
    }

    public List<ModelId> modelIds() {
        return models.stream().map(ModelId::parse).toList();
    }

    public PriceList prices() {
        Map<ModelId, PriceList.Price> prices = new java.util.HashMap<>();
        priceList.models().forEach((model, price) -> prices.put(ModelId.parse(model),
                new PriceList.Price(price.input(), price.output())));
        return new PriceList(priceList.version(), prices);
    }

    public record PriceListSettings(@DefaultValue("none") String version,
            @DefaultValue Map<String, PriceSettings> models) {
    }

    /** USD per million tokens. */
    public record PriceSettings(BigDecimal input, BigDecimal output) {
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentSettings.class)
    static class Registration {
    }
}
