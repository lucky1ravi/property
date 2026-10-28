package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
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

    @Override
    public String subscriber() {
        return "workflow.notifications";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("NotificationRequested");
    }

    @Override
    @Transactional
    public void handle(DomainEvent e) {
        String audience = e.text("audience"), target = e.text("target");
        if (audience == null || target == null) return;
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
        for (UUID u : users) {
            UUID nid = UUID.randomUUID();
            if (inApp)
                db.update("insert into workflow.notification (id, org_id, user_id, title, body, link, priority) values (?,?,?,?,?,?,?)", nid, e.orgId(), u, title, body, link, priority);
            String addr = "user-" + u.toString().substring(0, 8) + "@example.com";
            try {
                JsonNode usr = client.get(ServiceClient.Service.IDENTITY, "/internal/users/" + u);
                if (usr.hasNonNull("email")) addr = usr.path("email").asText();
            } catch (Exception ignored) {
            }
            if (email) delivery(e, nid, "EMAIL", addr, title, body, quiet && !"HIGH".equals(priority));
            if (sms && "HIGH".equals(priority)) delivery(e, nid, "SMS", "+1 (555) 010-" + String.format("%04d", Math.abs(u.hashCode() % 10000)), null, shorten(title + ": " + body), false);
        }
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
