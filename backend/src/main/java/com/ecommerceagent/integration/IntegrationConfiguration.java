package com.ecommerceagent.integration;

import java.net.InetAddress;
import java.time.Clock;

import com.ecommerceagent.integration.meta.MetaGraphClient;
import com.ecommerceagent.integration.woocommerce.StoreUrlPolicy;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IntegrationProperties.class, MetaProperties.class})
class IntegrationConfiguration {

    @Bean
    SecretCipher secretCipher(IntegrationProperties properties) {
        return new SecretCipher(properties.secretsKey(), properties.secretsKeyId(), properties.previousSecretsKeys());
    }

    @Bean
    WooCommerceClient wooCommerceClient(IntegrationProperties properties, Clock clock) {
        IntegrationProperties.WooCommerce woo = properties.woocommerce();
        StoreUrlPolicy policy = new StoreUrlPolicy(woo.allowInsecureHttp(), woo.allowPrivateNetwork(),
                InetAddress::getAllByName);
        return new WooCommerceClient(policy, woo.connectTimeout(), woo.responseTimeout(), woo.maxResponseBytes(), clock);
    }

    /** The Graph API client; a base URL other than Meta's host is refused outside the local and test profiles. */
    @Bean
    MetaGraphClient metaGraphClient(MetaProperties meta, Environment environment) {
        if (!meta.usesMetaHost() && !environment.acceptsProfiles(Profiles.of("local", "test"))) {
            throw new IllegalStateException("operator.integration.meta.graph-base-url may point away from "
                    + MetaProperties.META_HOST + " only in the local and test profiles");
        }
        return new MetaGraphClient(meta.graphBaseUrl(), meta.apiVersion(), meta.connectTimeout(), meta.timeout(),
                meta.maxResponseBytes());
    }
}
