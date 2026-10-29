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

    /**
     * Sends an in-app notification to a resident, owner or vendor.
     *
     * @param partyId the person; nothing is sent when null
     * @param title   short headline
     * @param body    message text
     * @param link    where the notification should open
     */
    public void toParty(UUID partyId, String title, String body, String link) {
        if (partyId != null) send("PARTY", partyId.toString(), title, body, link, "NORMAL");
    }

    /**
     * Sends an in-app notification to everyone holding a role in the company.
     *
     * @param role     the role key
     * @param title    short headline
     * @param body     message text
     * @param link     where the notification should open
     * @param priority {@code LOW}, {@code NORMAL} or {@code HIGH}
     */
    public void toRole(String role, String title, String body, String link, String priority) {
        send("ROLE", role, title, body, link, priority);
    }

    /**
     * Sends an in-app notification to one staff user.
     *
     * @param userId the user; nothing is sent when null
     * @param title  short headline
     * @param body   message text
     * @param link   where the notification should open
     */
    public void toUser(UUID userId, String title, String body, String link) {
        if (userId != null) send("USER", userId.toString(), title, body, link, "NORMAL");
    }
}
