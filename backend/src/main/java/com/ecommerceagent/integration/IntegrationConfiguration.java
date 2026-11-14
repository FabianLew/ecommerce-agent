package com.ecommerceagent.integration;

import java.net.InetAddress;
import java.time.Clock;

import com.ecommerceagent.integration.woocommerce.StoreUrlPolicy;
import com.ecommerceagent.integration.woocommerce.WooCommerceClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IntegrationProperties.class)
class IntegrationConfiguration {

    @Bean
    SecretCipher secretCipher(IntegrationProperties properties) {
        return new SecretCipher(properties.secretsKey(), properties.secretsKeyId());
    }

    @Bean
    WooCommerceClient wooCommerceClient(IntegrationProperties properties, Clock clock) {
        IntegrationProperties.WooCommerce woo = properties.woocommerce();
        StoreUrlPolicy policy = new StoreUrlPolicy(woo.allowInsecureHttp(), woo.allowPrivateNetwork(),
                InetAddress::getAllByName);
        return new WooCommerceClient(policy, woo.connectTimeout(), woo.readTimeout(), woo.maxResponseBytes(), clock);
    }
}
