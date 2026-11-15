package com.ecommerceagent.web;

import com.ecommerceagent.identity.StoreRowGuard;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Makes sure a request thread never carries a store binding into the next request (ADR-0022 point 3): the
 * binding set by {@link StoreScopeArgumentResolver} is dropped before the handler and after completion.
 */
class StoreRowGuardInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        StoreRowGuard.clear();
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
            Exception ex) {
        StoreRowGuard.clear();
    }
}
