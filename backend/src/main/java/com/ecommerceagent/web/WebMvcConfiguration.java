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

    WebMvcConfiguration(CurrentUserArgumentResolver currentUser, StoreScopeArgumentResolver storeScope) {
        this.currentUser = currentUser;
        this.storeScope = storeScope;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(currentUser);
        resolvers.add(storeScope);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new StoreRowGuardInterceptor());
    }
}
