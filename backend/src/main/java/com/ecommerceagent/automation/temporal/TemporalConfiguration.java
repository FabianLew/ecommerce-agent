package com.ecommerceagent.automation.temporal;

import java.util.List;

import io.temporal.client.WorkflowClientOptions;
import io.temporal.spring.boot.TemporalOptionsCustomizer;
import io.temporal.worker.WorkerFactoryOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers store-context propagation and activity scope verification with the Temporal starter. */
@Configuration(proxyBeanMethods = false)
class TemporalConfiguration {

    @Bean
    TemporalOptionsCustomizer<WorkflowClientOptions.Builder> storeContextPropagation(
            StoreContextPropagator propagator) {
        return builder -> builder.setContextPropagators(List.of(propagator));
    }

    @Bean
    TemporalOptionsCustomizer<WorkerFactoryOptions.Builder> storeScopeVerification(
            StoreScopeActivityInterceptor interceptor) {
        return builder -> builder.setWorkerInterceptors(interceptor);
    }
}
