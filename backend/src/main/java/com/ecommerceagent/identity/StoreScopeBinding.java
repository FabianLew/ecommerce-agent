package com.ecommerceagent.identity;

import java.lang.reflect.Method;
import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.ClassFilter;
import org.springframework.aop.framework.autoproxy.AbstractBeanFactoryAwareAdvisingPostProcessor;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;

/**
 * Binds the {@link TrustedStoreScope} a bean method receives to the thread for the duration of the call (ADR-0022
 * point 3), so every query the method runs is checked by row-level security against the same store its code
 * filters by. A scope can only come from {@link StoreAccess}, so the binding is as trusted as the scope. The advice
 * runs before any existing advisor (transactions), so a transaction's connection is checked out already bound.
 * Calling a method with another store's scope while a store is bound is refused ({@link StoreRowGuard#bind}).
 * Calls inside one bean do not pass through the proxy, and final classes are not proxied; a job that walks stores
 * binds each store itself ({@link SystemStoreRunner}).
 */
public final class StoreScopeBinding extends AbstractBeanFactoryAwareAdvisingPostProcessor {

    private static final String APPLICATION_PACKAGE = "com.ecommerceagent.";

    public StoreScopeBinding() {
        StaticMethodMatcherPointcut pointcut = new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                return scopeIndex(method) >= 0;
            }

            @Override
            public ClassFilter getClassFilter() {
                // Final classes (records such as task beans) cannot be proxied; their callers bind the store.
                return type -> type.getName().startsWith(APPLICATION_PACKAGE) && type != StoreAccess.class
                        && !java.lang.reflect.Modifier.isFinal(type.getModifiers());
            }
        };
        MethodInterceptor bind = invocation -> {
            int index = scopeIndex(invocation.getMethod());
            Object argument = index < 0 ? null : invocation.getArguments()[index];
            if (!(argument instanceof TrustedStoreScope scope)) {
                return invocation.proceed();
            }
            try (StoreRowGuard.Binding ignored = StoreRowGuard.bind(scope)) {
                return invocation.proceed();
            }
        };
        this.advisor = new DefaultPointcutAdvisor(pointcut, bind);
        setBeforeExistingAdvisors(true);
        setProxyTargetClass(true);
    }

    private static int scopeIndex(Method method) {
        Class<?>[] types = method.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            if (types[i] == TrustedStoreScope.class) {
                return i;
            }
        }
        return -1;
    }
}
