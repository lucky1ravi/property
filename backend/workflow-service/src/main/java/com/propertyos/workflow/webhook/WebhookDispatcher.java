package com.propertyos.workflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * Sends webhook deliveries. Every couple of seconds it picks up deliveries that are due and tries them; the outcome of every try is written to the log.
 *
 * <p>Two layers of retrying, on purpose. Inside one try, a Resilience4j retry with exponential backoff absorbs a dropped connection or a brief 502/503/504.
 * If the try still fails, the delivery is scheduled for later (1 minute, 5 minutes, 30 minutes, 2 hours, 6 hours: the operator's {@code webhooks} setting),
 * which survives a restart and does not hold a thread while it waits. A delivery that runs out of tries, or that the receiver rejects for good (a 4xx other than
 * 408 and 429), is marked failed and can be retried by hand. A receiver that fails 20 deliveries in a row is switched off.</p>
 *
 * <p>Addresses are checked again just before sending, and redirects are never followed.</p>
 */
@Component
public class WebhookDispatcher {
    private static final Logger log = LoggerFactory.getLogger(WebhookDispatcher.class);
    private static final int CLAIM = 10;

    /** What one try produced. {@code code} is 0 when the receiver never answered. */
    record Result(int code, String error, long millis) {
        boolean success() {
            return code >= 200 && code < 300;
        }

        /** A failure worth trying again: nothing answered, the receiver was busy or timing out, or a gateway error. */
        boolean transientFailure() {
            return code == 0 || code == 408 || code == 429 || code >= 500;
        }
    }

    private final Db db;
    private final ConfigClient config;
    private final UrlGuard guard;
    private final TransactionTemplate tx;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "webhook-delivery"); t.setDaemon(true); return t; });

    public WebhookDispatcher(Db db, ConfigClient config, UrlGuard guard, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.guard = guard;
        this.tx = new TransactionTemplate(tm);
    }

    private <T> T asSystem(UUID org, Supplier<T> work) {
        return UserContextHolder.runAsSystem(org, () -> tx.execute(st -> work.get()));
    }

    static WebhookSettings settingsFor(ConfigClient config, UUID org) {
        JsonNode cfg = org == null ? config.defaults() : config.forOrg(org);
        return WebhookSettings.of(cfg.has("webhooks") ? cfg.get("webhooks") : config.defaults().path("webhooks"));
    }

    // ----------------------------------------------------------------------------------------------- the timer

    /** Claims deliveries that are due and sends them on a small pool. A claim pushes the delivery's next time out 90 seconds, so a crash never loses one and two servers never send the same one. */
    @Scheduled(fixedDelayString = "${propertyos.webhooks.poll-ms:2000}", initialDelay = 10_000)
    public void tick() {
        try {
            List<Map<String, Object>> due = asSystem(null, () -> db.list("""
                    update workflow.webhook_delivery set next_attempt_at = now() + interval '90 seconds'
                    where id in (select id from workflow.webhook_delivery where status = 'PENDING' and next_attempt_at <= now() order by next_attempt_at limit ? for update skip locked)
                    returning id, org_id""", CLAIM));
            for (Map<String, Object> d : due) pool.submit(() -> {
                try {
                    attempt((UUID) d.get("id"), (UUID) d.get("org_id"), true, false);
                } catch (RuntimeException e) {
                    log.warn("Webhook delivery {} failed unexpectedly: {}", d.get("id"), e.getMessage());
                }
            });
        } catch (RuntimeException e) {
            log.warn("Webhook timer failed: {}", e.getMessage());
        }
    }

    /** Once a day: deletes log entries older than each company's retention period. */
    @Scheduled(cron = "0 40 3 * * *", zone = "America/Chicago")
    public void cleanup() {
        try {
            List<UUID> orgs = asSystem(null, () -> db.list("select distinct org_id from workflow.webhook_delivery").stream().map(r -> (UUID) r.get("org_id")).toList());
            for (UUID org : orgs) {
                int days = settingsFor(config, org).retentionDays();
                asSystem(org, () -> db.update("delete from workflow.webhook_delivery where org_id = ? and created_at < now() - make_interval(days => ?) and status <> 'PENDING'", org, days));
            }
        } catch (RuntimeException e) {
            log.warn("Webhook log cleanup failed: {}", e.getMessage());
        }
    }

    // ----------------------------------------------------------------------------------------------- one try

    /**
     * Tries one delivery now and records the outcome.
     *
     * @param scheduleRetries when false (the test button) a failure is final instead of being scheduled again
     * @param ignoreInactive  true for a test, which may go to an endpoint that is switched off
     * @return the delivery as recorded
     */
    public Map<String, Object> attempt(UUID deliveryId, UUID org, boolean scheduleRetries, boolean ignoreInactive) {
        Map<String, Object> d = asSystem(org, () -> db.find("""
                select d.id, d.endpoint_id, d.event_id, d.event_type, d.payload::text as payload, d.attempts, e.url, e.secret, e.active
                from workflow.webhook_delivery d join workflow.webhook_endpoint e on e.id = d.endpoint_id where d.id = ? and d.org_id = ?""", deliveryId, org)).orElse(null);
        if (d == null) return Map.of();
        WebhookSettings s = settingsFor(config, org);
        int attemptsSoFar = ((Number) d.get("attempts")).intValue();
        Result r;
        if (!Boolean.TRUE.equals(d.get("active")) && !ignoreInactive) r = new Result(-1, "The endpoint is switched off.", 0);
        else if (!config.forOrg(org).path("features").path("WEBHOOKS").asBoolean(true)) r = new Result(-1, "Webhooks aren't included in the company's plan.", 0);
        else r = send(d, s, deliveryId);
        record(deliveryId, org, (UUID) d.get("endpoint_id"), attemptsSoFar + 1, r, scheduleRetries, s);
        return asSystem(org, () -> db.one("Delivery", "select id, status, attempts, response_code, last_error, duration_ms, next_attempt_at from workflow.webhook_delivery where id = ?", deliveryId));
    }

    private Result send(Map<String, Object> d, WebhookSettings s, UUID deliveryId) {
        URI uri;
        try {
            uri = guard.check((String) d.get("url"));   // looked up again: a name can be pointed somewhere new after it was approved
        } catch (ApiException e) {
            return new Result(-1, "Blocked: " + e.getMessage(), 0);
        }
        byte[] body = ((String) d.get("payload")).getBytes(StandardCharsets.UTF_8);
        // A short, in-process retry for a dropped connection or a gateway hiccup. Anything longer is scheduled, not waited for.
        Retry retry = Retry.of("webhook-" + deliveryId, RetryConfig.<Result>custom()
                .maxAttempts(3)
                .intervalFunction(IntervalFunction.ofExponentialBackoff(Duration.ofMillis(300), 2.0))
                .retryOnResult(res -> res instanceof Result x && (x.code() == 502 || x.code() == 503 || x.code() == 504))
                .retryOnException(e -> e instanceof IOException)
                .failAfterMaxAttempts(false)
                .build());
        try {
            return retry.executeCheckedSupplier(() -> once(uri, d, body, s));
        } catch (Throwable e) {
            return new Result(0, describe(e, s), 0);
        }
    }

    private Result once(URI uri, Map<String, Object> d, byte[] body, WebhookSettings s) throws IOException, InterruptedException {
        long ts = System.currentTimeMillis() / 1000;
        HttpRequest req = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(s.timeoutSeconds()))
                .header("Content-Type", "application/json")
                .header("User-Agent", "PropertyOS-Webhooks/1.0")
                .header("X-PropertyOS-Event", (String) d.get("event_type"))
                .header("X-PropertyOS-Event-Id", (String) d.get("event_id"))
                .header("X-PropertyOS-Delivery", String.valueOf(d.get("id")))
                .header("X-PropertyOS-Timestamp", String.valueOf(ts))
                .header("X-PropertyOS-Signature", WebhookSigner.header((String) d.get("secret"), ts, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        long t0 = System.nanoTime();
        HttpResponse<java.io.InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        long ms = (System.nanoTime() - t0) / 1_000_000;
        String snippet = "";
        try (java.io.InputStream in = res.body()) {
            snippet = new String(in.readNBytes(300), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();   // a little of the answer, for the log; never the whole thing
        }
        int code = res.statusCode();
        return new Result(code, code >= 200 && code < 300 ? null : "The receiver answered " + code + (snippet.isEmpty() ? "" : ": " + snippet), ms);
    }

    private static String describe(Throwable e, WebhookSettings s) {
        if (e instanceof java.net.http.HttpTimeoutException) return "No answer within " + s.timeoutSeconds() + " seconds.";
        if (e instanceof java.net.ConnectException) return "Could not connect to the receiver.";
        if (e instanceof javax.net.ssl.SSLException) return "The receiver's certificate could not be verified.";
        if (e instanceof InterruptedException) { Thread.currentThread().interrupt(); return "Interrupted."; }
        return "Could not reach the receiver: " + String.valueOf(e.getMessage()).replaceAll("\\s+", " ");
    }

    /** Writes the outcome: delivered, scheduled again, or failed for good (which counts against the endpoint). */
    private void record(UUID id, UUID org, UUID endpoint, int attempts, Result r, boolean scheduleRetries, WebhookSettings s) {
        String err = r.error() == null ? null : (r.error().length() > 480 ? r.error().substring(0, 480) : r.error());
        Integer code = r.code() > 0 ? r.code() : null;
        asSystem(org, () -> {
            if (r.success()) {
                db.update("update workflow.webhook_delivery set status = 'SUCCEEDED', attempts = ?, response_code = ?, duration_ms = ?, last_error = null, last_attempt_at = now(), delivered_at = now(), next_attempt_at = null where id = ?",
                        attempts, code, (int) r.millis(), id);
                db.update("update workflow.webhook_endpoint set consecutive_failures = 0, last_delivery_at = now() where id = ?", endpoint);
                return null;
            }
            boolean again = scheduleRetries && r.transientFailure() && r.code() != -1 && !s.exhausted(attempts);
            if (again) {
                db.update("update workflow.webhook_delivery set status = 'PENDING', attempts = ?, response_code = ?, duration_ms = ?, last_error = ?, last_attempt_at = now(), next_attempt_at = now() + make_interval(secs => ?) where id = ?",
                        attempts, code, (int) r.millis(), err, s.delayAfter(attempts), id);
                return null;
            }
            db.update("update workflow.webhook_delivery set status = 'FAILED', attempts = ?, response_code = ?, duration_ms = ?, last_error = ?, last_attempt_at = now(), next_attempt_at = null where id = ?",
                    attempts, code, (int) r.millis(), err, id);
            // A final failure counts against the endpoint. A test does not: someone is looking at it.
            if (scheduleRetries) {
                db.update("update workflow.webhook_endpoint set consecutive_failures = consecutive_failures + 1, last_delivery_at = now() where id = ?", endpoint);
                db.update("""
                        update workflow.webhook_endpoint set active = false, disabled_reason = 'Switched off after ' || consecutive_failures || ' failed deliveries in a row.', updated_at = now()
                        where id = ? and active and consecutive_failures >= ?""", endpoint, s.autoDisableAfterFailures());
            }
            return null;
        });
    }
}
