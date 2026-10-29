package com.propertyos.common.events;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Requests a notification by publishing an event; the workflow service delivers it to the
 * in-app inbox and the simulated email/SMS channels, honouring the organisation's settings.
 */
@Component
public class Notifier {
    private final EventPublisher events;

    public Notifier(EventPublisher events) {
        this.events = events;
    }

    /**
     * @param audience  USER, PARTY or ROLE
     * @param target    user id, party id (owner/tenant/vendor) or role name
     */
    public void send(String audience, String target, String title, String body, String link, String priority) {
        Map<String, Object> p = new HashMap<>();
        p.put("audience", audience);
        p.put("target", target);
        p.put("title", title);
        p.put("body", body);
        p.put("link", link);
        p.put("priority", priority == null ? "NORMAL" : priority);
        events.publish("NotificationRequested", "Notification", UUID.randomUUID(), p);
    }

    /** Like {@link #send} with a category (drives each person's preferences) and a key that stops the same alert being sent twice. */
    public void sendAlert(String audience, String target, String title, String body, String link, String priority, String category, String dedupeKey) {
        Map<String, Object> p = new HashMap<>();
        p.put("audience", audience);
        p.put("target", target);
        p.put("title", title);
        p.put("body", body);
        p.put("link", link);
        p.put("priority", priority == null ? "NORMAL" : priority);
        p.put("category", category);
        p.put("dedupeKey", dedupeKey);
        events.publish("NotificationRequested", "Notification", UUID.randomUUID(), p);
    }

    public void toParty(UUID partyId, String title, String body, String link) {
        if (partyId != null) send("PARTY", partyId.toString(), title, body, link, "NORMAL");
    }

    public void toRole(String role, String title, String body, String link, String priority) {
        send("ROLE", role, title, body, link, priority);
    }

    public void toUser(UUID userId, String title, String body, String link) {
        if (userId != null) send("USER", userId.toString(), title, body, link, "NORMAL");
    }
}
