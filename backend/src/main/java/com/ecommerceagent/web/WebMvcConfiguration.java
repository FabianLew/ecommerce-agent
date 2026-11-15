package com.ecommerceagent.web;

import java.util.List;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
class WebMvcConfiguration implements WebMvcConfigurer {

    private final CurrentUserArgumentResolver currentUser;
    private final StoreScopeArgumentResolver storeScope;
    private final ApiRateLimitInterceptor rateLimits;

    WebMvcConfiguration(CurrentUserArgumentResolver currentUser, StoreScopeArgumentResolver storeScope,
            ApiRateLimitInterceptor rateLimits) {
        this.currentUser = currentUser;
        this.storeScope = storeScope;
        this.rateLimits = rateLimits;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
        resolvers.add(storeScope);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StoreRowGuardInterceptor());
        registry.addInterceptor(rateLimits);
    }
}
