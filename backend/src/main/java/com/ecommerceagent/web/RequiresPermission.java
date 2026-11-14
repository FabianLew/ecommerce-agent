package com.ecommerceagent.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import com.ecommerceagent.identity.StorePermission;

/**
 * Store permission required for a {@link com.ecommerceagent.identity.TrustedStoreScope} parameter.
 * Without it the endpoint requires {@link StorePermission#STORE_READ} (ADR-0004).
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@interface RequiresPermission {

    StorePermission value();
}
