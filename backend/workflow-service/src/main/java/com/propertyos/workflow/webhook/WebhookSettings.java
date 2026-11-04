package com.propertyos.workflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * The operator's webhook settings (the {@code webhooks} product default): how many times a delivery is tried, how long to wait between tries, the
 * request timeout, how many endpoints a company may have, when a failing endpoint is switched off, and how long the log is kept. Bad values are clamped.
 */
public record WebhookSettings(int maxAttempts, List<Integer> backoffSeconds, int timeoutSeconds, int maxPerCompany, int autoDisableAfterFailures, int retentionDays) {

    public static WebhookSettings of(JsonNode n) {
        List<Integer> backoff = new ArrayList<>();
        JsonNode b = n == null ? null : n.path("backoffSeconds");
        if (b != null && b.isArray()) b.forEach(x -> { if (x.isNumber() && x.asInt() >= 1) backoff.add(Math.min(x.asInt(), 2 * 86_400)); });
        if (backoff.isEmpty()) backoff.addAll(List.of(60, 300, 1800, 7200, 21600));
        return new WebhookSettings(num(n, "maxAttempts", 6, 1, 10), backoff, num(n, "timeoutSeconds", 5, 1, 30), num(n, "maxEndpoints", 10, 1, 100),
                num(n, "autoDisableAfterFailures", 20, 1, 1000), num(n, "retentionDays", 30, 1, 365));
    }

    private static int num(JsonNode n, String key, int fallback, int min, int max) {
        JsonNode v = n == null ? null : n.path(key);
        if (v == null || !v.isNumber()) return fallback;
        return Math.max(min, Math.min(max, v.asInt()));
    }

    /** True when {@code attemptsMade} tries have used up the allowance. */
    public boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    /** Seconds to wait before the next try, after {@code attemptsMade} tries have failed. The last backoff step repeats if there are more tries than steps. */
    public int delayAfter(int attemptsMade) {
        return backoffSeconds.get(Math.max(0, Math.min(attemptsMade - 1, backoffSeconds.size() - 1)));
    }
}
