package com.ecommerceagent.automation.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExecutionSettings.class)
class ExecutionConfiguration {
}
