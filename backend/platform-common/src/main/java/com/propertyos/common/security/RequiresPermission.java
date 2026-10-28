package com.propertyos.common.security;

import java.lang.annotation.*;

/**
 * Declares the FEATURE:ACTION permission an endpoint needs, for example "LEASE:APPROVE".
 * Several values mean any one of them is enough.
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RequiresPermission {
    String[] value();
}
