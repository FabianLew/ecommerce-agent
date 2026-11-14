package com.ecommerceagent;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** All application time is UTC (CLAUDE.md). Inject {@link Clock} instead of calling {@code now()}. */
@Configuration(proxyBeanMethods = false)
class TimeConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
