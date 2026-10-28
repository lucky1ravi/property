package com.propertyos.common.security;

import com.propertyos.common.web.ApiException;

import java.util.UUID;
import java.util.function.Supplier;

/** Thread-bound access to the current caller. */
public final class UserContextHolder {
    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    public static UserContext get() {
        return CURRENT.get();
    }

    public static UserContext require() {
        UserContext ctx = CURRENT.get();
        if (ctx == null) throw ApiException.unauthorized("Sign-in required");
        return ctx;
    }

    public static UUID orgId() {
        return require().orgId();
    }

    public static void set(UserContext ctx) {
        CURRENT.set(ctx);
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Run work as the given context, restoring the previous one afterwards. */
    public static <T> T runAs(UserContext ctx, Supplier<T> work) {
        UserContext previous = CURRENT.get();
        CURRENT.set(ctx);
        try {
            return work.get();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    public static void runAs(UserContext ctx, Runnable work) {
        runAs(ctx, () -> {
            work.run();
            return null;
        });
    }

    /** Run as the system for one organisation (used by event handlers and jobs). */
    public static <T> T runAsSystem(UUID orgId, Supplier<T> work) {
        return runAs(UserContext.system(orgId), work);
    }
}
