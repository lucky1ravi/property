package com.propertyos.perf;

/**
 * Settings for a run, read from system properties so the same simulations work on a laptop, in CI and against a deployed environment.
 * Pass them to Maven with {@code -Dname=value}.
 */
final class Config {
    private Config() {
    }

    /** Gateway address; every call goes through it, as real clients do. */
    static final String BASE_URL = text("baseUrl", "http://localhost:8080");
    /** Demo company whose personas sign in. */
    static final String ORG = text("org", "Summit");
    /** Concurrent users (or users per second for open-model simulations). */
    static final int USERS = number("users", 20);
    /** Seconds to reach full load. */
    static final int RAMP_SECONDS = number("rampSeconds", 30);
    /** Seconds to stay at full load. */
    static final int HOLD_SECONDS = number("holdSeconds", 60);
    /** Service level: 95% of requests must finish within this many milliseconds. */
    static final int P95_MS = number("p95Ms", 800);
    /** Service level: the share of failed requests must stay below this percent. */
    static final double MAX_ERROR_PERCENT = Double.parseDouble(text("maxErrorPercent", "1"));

    private static String text(String name, String fallback) {
        String v = System.getProperty(name);
        return v == null || v.isBlank() || v.startsWith("${") ? fallback : v;
    }

    private static int number(String name, int fallback) {
        return Integer.parseInt(text(name, String.valueOf(fallback)));
    }
}
