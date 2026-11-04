package com.propertyos.workflow.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * A company's webhook endpoints: add, change, switch on and off, rotate the secret, delete; send a test event; read the delivery log and retry a failed
 * delivery. Everything runs as the signed-in person, so row-level security keeps each company to its own endpoints and log. Needs the WEBHOOKS plan feature
 * and the WEBHOOK permissions. An endpoint's secret is returned once, when it is made or rotated, and never again.
 */
@Service
public class WebhookService {
    private final Db db;
    private final AuditService audit;
    private final ConfigClient config;
    private final ObjectMapper mapper;
    private final UrlGuard guard;
    private final WebhookDispatcher dispatcher;
    private final TransactionTemplate tx;

    public WebhookService(Db db, AuditService audit, ConfigClient config, ObjectMapper mapper, UrlGuard guard, WebhookDispatcher dispatcher, PlatformTransactionManager tm) {
        this.db = db;
        this.audit = audit;
        this.config = config;
        this.mapper = mapper;
        this.guard = guard;
        this.dispatcher = dispatcher;
        this.tx = new TransactionTemplate(tm);
    }

    private void requirePlan() {
        if (!config.featureEnabled("WEBHOOKS")) throw ApiException.forbidden("Webhooks aren't included in your company's plan. Ask PropertyOS about adding them.");
    }

    /** The events that can be subscribed to, for the form. */
    public List<Map<String, Object>> events() {
        requirePlan();
        List<Map<String, Object>> out = new ArrayList<>();
        for (WebhookEventTypes.Type t : WebhookEventTypes.ALL) out.add(Map.of("key", t.key(), "label", t.label(), "description", t.description()));
        return out;
    }

    private static final String ENDPOINT_COLUMNS = """
            e.id, e.name, e.url, array_to_string(e.events, ',') as events_csv, e.active, e.disabled_reason, e.consecutive_failures, e.last_delivery_at, e.created_by, e.created_at, e.updated_at,
            (select count(*) from workflow.webhook_delivery d where d.endpoint_id = e.id and d.status = 'SUCCEEDED' and d.created_at > now() - interval '24 hours') as delivered_24h,
            (select count(*) from workflow.webhook_delivery d where d.endpoint_id = e.id and d.status = 'FAILED' and d.created_at > now() - interval '24 hours') as failed_24h,
            (select count(*) from workflow.webhook_delivery d where d.endpoint_id = e.id and d.status = 'PENDING') as pending,
            (select d.status from workflow.webhook_delivery d where d.endpoint_id = e.id order by d.created_at desc limit 1) as last_status,
            (select d.response_code from workflow.webhook_delivery d where d.endpoint_id = e.id order by d.created_at desc limit 1) as last_response_code""";

    private static Map<String, Object> shaped(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        String csv = (String) m.remove("events_csv");
        m.put("events", csv == null || csv.isEmpty() ? List.of() : List.of(csv.split(",")));
        return m;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        requirePlan();
        return db.list("select " + ENDPOINT_COLUMNS + " from workflow.webhook_endpoint e order by e.created_at desc").stream().map(WebhookService::shaped).toList();
    }

    private Map<String, Object> one(UUID id) {
        return shaped(db.one("Webhook endpoint", "select " + ENDPOINT_COLUMNS + " from workflow.webhook_endpoint e where e.id = ?", id));
    }

    private static List<String> eventKeys(Object raw) {
        if (!(raw instanceof Collection<?> c) || c.isEmpty()) throw ApiException.badRequest("Choose at least one event to send.");
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        for (Object o : c) {
            String k = String.valueOf(o);
            if (WebhookEventTypes.byKey(k).isEmpty()) throw ApiException.badRequest("\"" + k + "\" is not an event you can subscribe to.");
            keys.add(k);
        }
        return new ArrayList<>(keys);
    }

    private static String name(String n) {
        String v = n == null ? "" : n.trim();
        if (v.isEmpty()) throw ApiException.badRequest("Give the endpoint a name, such as \"CRM\" or \"Slack alerts\".");
        if (v.length() > 120) throw ApiException.badRequest("Keep the name under 120 characters.");
        return v;
    }

    /**
     * Adds an endpoint.
     *
     * @param b name, url, events (list of event keys)
     * @return the endpoint plus its signing {@code secret}, shown only this once
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        requirePlan();
        UserContext ctx = UserContextHolder.require();
        String name = name(Req.str(b, "name"));
        String url = guard.check(Req.str(b, "url")).toString();
        List<String> events = eventKeys(b.get("events"));
        int max = WebhookDispatcher.settingsFor(config, ctx.orgId()).maxPerCompany();
        if (db.value(Long.class, "select count(*) from workflow.webhook_endpoint") >= max) throw ApiException.conflict("You have reached the limit of " + max + " webhook endpoints. Delete one first.");
        UUID id = UUID.randomUUID();
        String secret = WebhookSigner.newSecret();
        db.update("insert into workflow.webhook_endpoint (id, org_id, name, url, secret, events, created_by) values (?,?,?,?,?, string_to_array(?, ','), ?)",
                id, ctx.orgId(), name, url, secret, String.join(",", events), ctx.name());
        audit.record("WebhookEndpoint", id, "WEBHOOK_CREATED", null, "ACTIVE", Map.of("name", name, "events", String.join(",", events), "by", ctx.name()));
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("secret", secret);
        return out;
    }

    /** Changes any of name, url, events and active. Switching an endpoint back on clears its failure count. */
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> b) {
        requirePlan();
        Map<String, Object> cur = one(id);
        String name = b.containsKey("name") ? name(Req.str(b, "name")) : (String) cur.get("name");
        String url = b.containsKey("url") ? guard.check(Req.str(b, "url")).toString() : (String) cur.get("url");
        @SuppressWarnings("unchecked") List<String> events = b.containsKey("events") ? eventKeys(b.get("events")) : (List<String>) cur.get("events");
        boolean active = b.containsKey("active") ? Req.bool(b, "active") : (Boolean) cur.get("active");
        boolean reenabled = active && !Boolean.TRUE.equals(cur.get("active"));
        db.update("""
                update workflow.webhook_endpoint set name = ?, url = ?, events = string_to_array(?, ','), active = ?, updated_at = now(),
                    disabled_reason = case when ? then null when ? then disabled_reason else 'Switched off by ' || ? end,
                    consecutive_failures = case when ? then 0 else consecutive_failures end
                where id = ?""", name, url, String.join(",", events), active, active, !active && !Boolean.TRUE.equals(cur.get("active")), UserContextHolder.require().name(), reenabled, id);
        audit.record("WebhookEndpoint", id, active ? "WEBHOOK_UPDATED" : "WEBHOOK_DISABLED", null, active ? "ACTIVE" : "DISABLED", Map.of("by", UserContextHolder.require().name()));
        return one(id);
    }

    /** Makes a new secret; the old one stops being used at once (the next delivery is signed with the new one). Returned once. */
    @Transactional
    public Map<String, Object> rotateSecret(UUID id) {
        requirePlan();
        one(id);
        String secret = WebhookSigner.newSecret();
        db.update("update workflow.webhook_endpoint set secret = ?, updated_at = now() where id = ?", secret, id);
        audit.record("WebhookEndpoint", id, "WEBHOOK_SECRET_ROTATED", null, null, Map.of("by", UserContextHolder.require().name()));
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("secret", secret);
        return out;
    }

    /** Removes an endpoint and its delivery log. */
    @Transactional
    public Map<String, Object> delete(UUID id) {
        requirePlan();
        one(id);
        db.update("delete from workflow.webhook_endpoint where id = ?", id);
        audit.record("WebhookEndpoint", id, "WEBHOOK_DELETED", "ACTIVE", null, Map.of("by", UserContextHolder.require().name()));
        return Map.of("deleted", true);
    }

    /**
     * Sends a sample event to an endpoint right now, once, and reports what the receiver said. It is signed and shaped exactly like a real delivery, marked
     * {@code "test": true}, and works on an endpoint that is switched off so it can be checked before it is turned on. A failed test is not retried and does
     * not count against the endpoint.
     *
     * @param event the event to imitate; the endpoint's first subscribed event when omitted
     */
    public Map<String, Object> sendTest(UUID id, String event) {
        requirePlan();
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> ep = tx.execute(st -> one(id));
        @SuppressWarnings("unchecked") List<String> subscribed = (List<String>) ep.get("events");
        String key = event == null || event.isBlank() ? subscribed.get(0) : event;
        if (WebhookEventTypes.byKey(key).isEmpty()) throw ApiException.badRequest("\"" + key + "\" is not an event you can test.");
        UUID deliveryId = UUID.randomUUID();
        String eventId = "evt_test_" + deliveryId.toString().substring(0, 8);
        Map<String, Object> sample = WebhookEventTypes.sample(key);
        UUID aggregate = UUID.fromString((String) sample.get("id"));
        String body = WebhookEvents.envelope(mapper, eventId, key, OffsetDateTime.now(), aggregate, sample, true);
        tx.executeWithoutResult(st -> db.update("""
                insert into workflow.webhook_delivery (id, org_id, endpoint_id, event_id, event_type, payload, status, test)
                values (?,?,?,?,?,?::jsonb,'PENDING', true)""", deliveryId, ctx.orgId(), id, eventId, key, body));   // no next_attempt_at: the timer leaves it alone
        audit.record("WebhookEndpoint", id, "WEBHOOK_TEST_SENT", null, null, Map.of("event", key, "by", ctx.name()));
        Map<String, Object> result = dispatcher.attempt(deliveryId, ctx.orgId(), false, true);
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("event_type", key);
        return out;
    }

    // ----------------------------------------------------------------------------------------------- the delivery log

    /** The delivery log, newest first, paged. Filter by endpoint and by status (PENDING, SUCCEEDED, FAILED). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deliveries(UUID endpointId, String status) {
        requirePlan();
        StringBuilder sql = new StringBuilder("""
                select d.id, d.endpoint_id, e.name as endpoint_name, e.url as endpoint_url, d.event_id, d.event_type, d.status, d.attempts, d.response_code, d.last_error,
                       d.duration_ms, d.next_attempt_at, d.last_attempt_at, d.delivered_at, d.test, d.created_at
                from workflow.webhook_delivery d join workflow.webhook_endpoint e on e.id = d.endpoint_id where 1 = 1""");
        List<Object> args = new ArrayList<>();
        if (endpointId != null) { sql.append(" and d.endpoint_id = ?"); args.add(endpointId); }
        if (status != null && !status.isBlank()) {
            String s = status.trim().toUpperCase();
            if (!List.of("PENDING", "SUCCEEDED", "FAILED").contains(s)) throw ApiException.badRequest("Status is pending, succeeded or failed.");
            sql.append(" and d.status = ?");
            args.add(s);
        }
        sql.append(" order by d.created_at desc");
        return db.page(sql.toString(), args.toArray());
    }

    /** One delivery with the exact JSON that is sent. */
    @Transactional(readOnly = true)
    public Map<String, Object> delivery(UUID id) {
        requirePlan();
        return db.one("Delivery", """
                select d.id, d.endpoint_id, e.name as endpoint_name, e.url as endpoint_url, d.event_id, d.event_type, d.status, d.attempts, d.response_code, d.last_error,
                       d.duration_ms, d.next_attempt_at, d.last_attempt_at, d.delivered_at, d.test, d.created_at, d.payload::text as payload
                from workflow.webhook_delivery d join workflow.webhook_endpoint e on e.id = d.endpoint_id where d.id = ?""", id);
    }

    /** Puts a failed (or waiting) delivery back in the queue to be tried now. */
    @Transactional
    public Map<String, Object> retry(UUID id) {
        requirePlan();
        Map<String, Object> d = delivery(id);
        if ("SUCCEEDED".equals(d.get("status"))) throw ApiException.conflict("That delivery already succeeded.");
        db.update("update workflow.webhook_delivery set status = 'PENDING', next_attempt_at = now() where id = ?", id);
        audit.record("WebhookEndpoint", (UUID) d.get("endpoint_id"), "WEBHOOK_DELIVERY_RETRIED", (String) d.get("status"), "PENDING", Map.of("delivery", id.toString(), "by", UserContextHolder.require().name()));
        return Map.of("queued", true);
    }
}
