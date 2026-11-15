package com.ecommerceagent.identity;

import javax.sql.DataSource;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Row-level security support (ADR-0022): the data source applies the bound scope to every connection, and bean
 * methods that receive a {@link TrustedStoreScope} bind it for their call. On unless switched off locally.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "operator.row-security.enabled", havingValue = "true", matchIfMissing = true)
class RowSecurityConfiguration {

    @Bean
    static BeanPostProcessor rowSecurityDataSourceWrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof DataSource dataSource && !(bean instanceof RowSecurityDataSource)) {
                    return new RowSecurityDataSource(dataSource);
                }
                return bean;
            }
        };
    }

    @Bean
    static StoreScopeBinding storeScopeBinding() {
        return new StoreScopeBinding();
    }
}
