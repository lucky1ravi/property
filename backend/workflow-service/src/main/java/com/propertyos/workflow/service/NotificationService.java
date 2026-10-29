package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.*;

/** Delivers NotificationRequested events: in-app inbox plus simulated email and SMS, honouring channel and quiet-hours settings. */
@Component
public class NotificationService implements EventHandler {
    private final Db db;
    private final ServiceClient client;
    private final ConfigClient config;

    public NotificationService(Db db, ServiceClient client, ConfigClient config) {
        this.db = db;
        this.client = client;
        this.config = config;
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "workflow.notifications";
    }

    /**
     * The event that asks for a notification to be delivered.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("NotificationRequested");
    }

    /**
     * Delivers a requested notification to its user, role or party according to their preferences, skipping duplicates.
     */
    @Override
    @Transactional
    public void handle(DomainEvent e) {
        String audience = e.text("audience"), target = e.text("target");
        if (audience == null || target == null) return;
        String dedupe = e.text("dedupeKey");
        if (dedupe != null && db.update("insert into workflow.notification_dedupe (org_id, dedupe_key) values (?, ?) on conflict do nothing", e.orgId(), dedupe) == 0) return;   // already sent
        List<UUID> users = new ArrayList<>();
        try {
            JsonNode n = client.get(ServiceClient.Service.IDENTITY, "/internal/audience?type=" + audience + "&target=" + java.net.URLEncoder.encode(target, java.nio.charset.StandardCharsets.UTF_8));
            for (JsonNode u : n) users.add(UUID.fromString(u.asText()));
        } catch (Exception ex) {
            throw new IllegalStateException("Could not resolve notification audience", ex);
        }
        String title = e.text("title"), body = e.text("body"), link = e.text("link"), priority = Optional.ofNullable(e.text("priority")).orElse("NORMAL");
        boolean email = config.flag("notifications.channels.email", true), sms = config.flag("notifications.channels.sms", true);
        boolean inApp = config.flag("notifications.channels.inApp", true);
        String qStart = config.text("notifications.quietHours.start", "21:00"), qEnd = config.text("notifications.quietHours.end", "08:00");
        boolean quiet = isQuiet(qStart, qEnd);
        String category = e.text("category") != null ? e.text("category") : categoryOf(link);
        for (UUID u : users) {
            UUID nid = UUID.randomUUID();
            // Personal preferences. Urgent (HIGH) alerts always reach the in-app inbox so nothing critical can be muted by accident.
            boolean[] pref = preference(u, category);
            boolean urgent = "HIGH".equals(priority);
            boolean deliverInApp = inApp && (pref[0] || urgent), deliverEmail = email && pref[1], deliverSms = sms && pref[2];
            if (deliverInApp)
                db.update("insert into workflow.notification (id, org_id, user_id, title, body, link, priority, category) values (?,?,?,?,?,?,?,?)", nid, e.orgId(), u, title, body, link, priority, category);
            if (!deliverInApp && !deliverEmail && !deliverSms) continue;
            String addr = "user-" + u.toString().substring(0, 8) + "@example.com";
            try {
                JsonNode usr = client.get(ServiceClient.Service.IDENTITY, "/internal/users/" + u);
                if (usr.hasNonNull("email")) addr = usr.path("email").asText();
            } catch (Exception ignored) {
            }
            UUID did = deliverInApp ? nid : null;
            if (deliverEmail) delivery(e, did, "EMAIL", addr, title, body, quiet && !"HIGH".equals(priority));
            if (deliverSms && "HIGH".equals(priority)) delivery(e, did, "SMS", "+1 (555) 010-" + String.format("%04d", Math.abs(u.hashCode() % 10000)), null, shorten(title + ": " + body), false);
        }
    }

    /** {in-app, email, sms} for this person and category; everything is on until they change it. */
    boolean[] preference(UUID user, String category) {
        List<Map<String, Object>> r = db.list("select in_app, email, sms from workflow.notification_pref where user_id = ? and category = ?", user, category);
        if (r.isEmpty()) return new boolean[]{true, true, true};
        return new boolean[]{(Boolean) r.get(0).get("in_app"), (Boolean) r.get(0).get("email"), (Boolean) r.get(0).get("sms")};
    }

    static String categoryOf(String link) {
        if (link == null) return "GENERAL";
        if (link.startsWith("/payments")) return "PAYMENTS";
        if (link.startsWith("/maintenance")) return "MAINTENANCE";
        if (link.startsWith("/leasing") || link.startsWith("/my-lease")) return "LEASING";
        if (link.startsWith("/messages")) return "MESSAGES";
        if (link.startsWith("/documents")) return "DOCUMENTS";
        return "GENERAL";
    }

    private void delivery(DomainEvent e, UUID nid, String channel, String to, String subject, String body, boolean heldForQuiet) {
        db.update("insert into workflow.delivery (id, org_id, notification_id, channel, recipient, subject, body, status, note) values (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), e.orgId(), nid, channel, to, subject, body, heldForQuiet ? "QUEUED" : "SENT",
                heldForQuiet ? "Held until quiet hours end" : "Simulated delivery (demo environment, nothing leaves the system)");
    }

    private static String shorten(String s) {
        return s.length() <= 140 ? s : s.substring(0, 137) + "...";
    }

    static boolean isQuiet(String start, String end) {
        try {
            LocalTime now = LocalTime.now(java.time.ZoneId.of("America/Chicago")), s = LocalTime.parse(start), en = LocalTime.parse(end);
            return s.isAfter(en) ? !now.isBefore(s) || now.isBefore(en) : !now.isBefore(s) && now.isBefore(en);
        } catch (Exception ex) {
            return false;
        }
    }

    // ---------- Inbox API ----------
    @Service
    public static class Inbox {
        private final Db db;

        public Inbox(Db db) {
            this.db = db;
        }

        @Transactional(readOnly = true)
        public Map<String, Object> mine() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("items", db.list("select * from workflow.notification where user_id = ? order by created_at desc limit 50", UserContextHolder.require().userId()));
            out.put("unread", db.value(Long.class, "select count(*) from workflow.notification where user_id = ? and read_at is null", UserContextHolder.require().userId()));
            return out;
        }

        /**
         * Notification categories as key, title and description, shown in preferences.
         */
        public static final List<String[]> CATEGORIES = List.of(
                new String[]{"PAYMENTS", "Payments and invoices", "Rent, receipts, declined payments, new charges and owner statements"},
                new String[]{"MAINTENANCE", "Repairs and work orders", "Requests, approvals, scheduling and escalations"},
                new String[]{"LEASING", "Leasing and applications", "Applications, showings, leases and renewals"},
                new String[]{"MESSAGES", "Messages", "New messages in your conversations"},
                new String[]{"DOCUMENTS", "Documents and e-signatures", "Files shared with you, documents to review, signature requests and completed signatures"},
                new String[]{"COMPLIANCE", "Deadlines and compliance", "Lease expiry, deposit refund deadlines, insurance expiry, tax and 1099 dates"},
                new String[]{"GENERAL", "Everything else", "Other updates"});

        @Transactional(readOnly = true)
        public Map<String, Object> preferences() {
            UUID me = UserContextHolder.require().userId();
            Map<String, Map<String, Object>> saved = new HashMap<>();
            for (Map<String, Object> r : db.list("select category, in_app, email, sms from workflow.notification_pref where user_id = ?", me)) saved.put((String) r.get("category"), r);
            List<Map<String, Object>> cats = new ArrayList<>();
            for (String[] c : CATEGORIES) {
                Map<String, Object> s = saved.get(c[0]);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", c[0]); m.put("label", c[1]); m.put("description", c[2]);
                m.put("inApp", s == null || (Boolean) s.get("in_app")); m.put("email", s == null || (Boolean) s.get("email")); m.put("sms", s == null || (Boolean) s.get("sms"));
                cats.add(m);
            }
            return Map.of("categories", cats, "note", "Urgent alerts always appear in your inbox, even if the in-app switch is off.");
        }

        @Transactional
        @SuppressWarnings("unchecked")
        public Map<String, Object> savePreferences(List<Map<String, Object>> body) {
            UserContext u = UserContextHolder.require();
            Set<String> valid = new HashSet<>();
            for (String[] c : CATEGORIES) valid.add(c[0]);
            for (Map<String, Object> c : body) {
                String key = String.valueOf(c.get("key"));
                if (!valid.contains(key)) continue;
                db.update("""
                        insert into workflow.notification_pref (user_id, org_id, category, in_app, email, sms) values (?,?,?,?,?,?)
                        on conflict (user_id, category) do update set in_app = excluded.in_app, email = excluded.email, sms = excluded.sms, updated_at = now()""",
                        u.userId(), u.orgId(), key, Boolean.TRUE.equals(c.get("inApp")), Boolean.TRUE.equals(c.get("email")), Boolean.TRUE.equals(c.get("sms")));
            }
            return preferences();
        }

        @Transactional
        public void markRead(UUID id) {
            db.update("update workflow.notification set read_at = now() where id = ? and user_id = ? and read_at is null", id, UserContextHolder.require().userId());
        }

        @Transactional
        public void markAllRead() {
            db.update("update workflow.notification set read_at = now() where user_id = ? and read_at is null", UserContextHolder.require().userId());
        }

        @Transactional(readOnly = true)
        public List<Map<String, Object>> deliveries() {
            return db.list("select * from workflow.delivery order by created_at desc limit 200");
        }
    }
}
