package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The operator's view of every email and text message the platform sent, across all companies: which company, which channel,
 * when, and whether it went, was held for quiet hours, or was skipped. The list is operational only: the operator does not see what a
 * message said, and recipients are masked (a***@example.com), because a company's messages are that company's business. For support
 * work one delivery can be opened in full (whole recipient and subject, still never the text) by giving a reason, which is recorded in
 * the company's own audit log.
 * Only platform administrators reach it; their sessions are not limited to one company, so the usual row-level security lets
 * them read every company's rows.
 */
@Service
public class PlatformDeliveries {
    private final Db db;
    private final ServiceClient client;

    public PlatformDeliveries(Db db, ServiceClient client) {
        this.db = db;
        this.client = client;
    }

    /** A page of deliveries, newest first, each with the name of the company it was sent for. Filter by channel, status and so on. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(UUID orgId) {
        List<Map<String, Object>> rows = db.page("""
                select id, org_id, channel,
                       case when recipient like '%@%' then regexp_replace(recipient, '^(.)[^@]*@', '\\1***@') else regexp_replace(recipient, '.(?=.{2}$)', '*', 'g') end as recipient,
                       status, note, created_at
                from workflow.delivery""" + (orgId == null ? "" : " where org_id = ?") + " order by created_at desc", orgId == null ? new Object[0] : new Object[]{orgId});
        Map<String, String> names = names(rows);
        for (Map<String, Object> r : rows) r.put("org_name", names.getOrDefault(String.valueOf(r.get("org_id")), "Unknown company"));
        return rows;
    }

    /** What went out in the last 7 days, by channel and by outcome, so a sudden change stands out. */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("last7d", db.value(Long.class, "select count(*) from workflow.delivery where created_at > now() - interval '7 days'"));
        out.put("byChannel", db.list("select channel, count(*) as count from workflow.delivery where created_at > now() - interval '7 days' group by channel order by channel"));
        out.put("byStatus", db.list("select status, count(*) as count from workflow.delivery where created_at > now() - interval '7 days' group by status order by status"));
        out.put("held", db.value(Long.class, "select count(*) from workflow.delivery where status = 'QUEUED'"));
        out.put("companies", db.value(Long.class, "select count(distinct org_id) from workflow.delivery where created_at > now() - interval '7 days'"));
        return out;
    }

    /**
     * Shows one delivery in full for support work: the whole recipient address or number and the subject. It never shows the body.
     * The person must say why (a support request, for example), and the viewing is recorded against the company the message was
     * sent for, so the company can see in its own audit log that, when and why the operator looked.
     *
     * @throws ApiException (400) when no reason is given, (404) when the delivery does not exist
     */
    @Transactional
    public Map<String, Object> reveal(UUID id, String reason) {
        String why = reason == null ? "" : reason.trim();
        if (why.length() < 10) throw ApiException.badRequest("Say why you need to see this, in a few words, for example the support request it is about. It is recorded in the company's audit log.");
        if (why.length() > 300) throw ApiException.badRequest("Keep the reason under 300 characters.");
        List<Map<String, Object>> found = db.list("select id, org_id, channel, recipient, subject, status, note, created_at from workflow.delivery where id = ?", id);
        if (found.isEmpty()) throw ApiException.notFound("Delivery");
        Map<String, Object> d = new LinkedHashMap<>(found.get(0));
        UserContext who = UserContextHolder.require();
        // The record sits under the company's own organisation, so its administrators see it in their audit log.
        db.update("insert into platform.audit_event (org_id, entity_type, entity_id, action, actor_id, actor_name, details) values (?, 'Delivery', ?, 'PLATFORM_DELIVERY_VIEWED', ?, ?, ?::jsonb)",
                d.get("org_id"), id, who.userId(), who.name(), db.json(Map.of("reason", why, "shown", "recipient and subject", "channel", String.valueOf(d.get("channel")))));
        d.put("org_name", names(List.of(d)).getOrDefault(String.valueOf(d.get("org_id")), "Unknown company"));
        return d;
    }

    private Map<String, String> names(List<Map<String, Object>> rows) {
        Set<String> ids = new LinkedHashSet<>();
        for (Map<String, Object> r : rows) if (r.get("org_id") != null) ids.add(r.get("org_id").toString());
        Map<String, String> out = new HashMap<>();
        if (ids.isEmpty()) return out;
        try {
            // Asked as the system with no company, so the answer covers every company and not only the operator's own.
            JsonNode n = UserContextHolder.runAsSystem(null, () -> client.get(ServiceClient.Service.IDENTITY, "/internal/org-names?ids=" + String.join(",", ids)));
            n.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        } catch (RuntimeException ignored) {
            // the rows are still useful without names
        }
        return out;
    }
}
