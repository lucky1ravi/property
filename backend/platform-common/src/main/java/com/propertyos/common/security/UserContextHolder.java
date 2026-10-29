package com.propertyos.common.security;

import com.propertyos.common.web.ApiException;

import java.util.UUID;
import java.util.function.Supplier;

/** Thread-bound access to the current caller. */
public final class UserContextHolder {
    /**
     * The user for the request being handled on this thread.
     */
    private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /**
     * Returns the current user, or null when no one is signed in.
     */
    public static UserContext get() {
        return CURRENT.get();
    }

    /**
     * Returns the current user.
     *
     * @throws ApiException (401) when no one is signed in
     */
    public static UserContext require() {
        UserContext ctx = CURRENT.get();
        if (ctx == null) throw ApiException.unauthorized("Sign-in required");
        return ctx;
    }

    /**
     * Returns the signed-in user's company.
     *
     * @throws ApiException (401) when no one is signed in
     */
    public static UUID orgId() {
        return require().orgId();
    }

    /**
     * Sets the current user for this thread. Always pair with {@link #clear()}.
     */
    public static void set(UserContext ctx) {
        CURRENT.set(ctx);
    }

    /**
     * Removes the current user from this thread.
     */
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

    /**
     * Runs work as another user and restores the previous user afterwards.
     *
     * @param ctx  the user to act as
     * @param work the work to run
     */
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
