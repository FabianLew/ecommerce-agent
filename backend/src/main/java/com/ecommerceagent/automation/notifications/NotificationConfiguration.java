package com.ecommerceagent.automation.notifications;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(NotificationSettings.class)
class NotificationConfiguration {

    @Bean
    @ConditionalOnMissingBean(MailProvider.class)
    MailProvider loggingMailProvider() {
        return new LoggingMailProvider();
    }
}
