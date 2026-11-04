package com.propertyos.workflow.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/**
 * Turns "a lead was answered" into an email to the prospect. It only writes the email as a queued delivery (with its rendered page); {@link EmailDispatcher}
 * sends it, so a mail service that is slow or down never holds up the person clicking Send reply, and a failed send is retried and shown in the delivery log.
 */
@Component
public class LeadEmails implements EventHandler {
    private final Db db;
    private final ServiceClient client;
    private final String overrideTo;

    public LeadEmails(Db db, ServiceClient client, @Value("${propertyos.email.override-to:}") String overrideTo) {
        this.db = db;
        this.client = client;
        this.overrideTo = overrideTo.trim();
    }

    @Override
    public String subscriber() {
        return "workflow.lead-emails";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LeadResponded");
    }

    @Override
    @Transactional
    public void handle(DomainEvent e) {
        String to = e.text("email"), reply = e.text("reply");
        if (to == null || to.isBlank() || reply == null || reply.isBlank()) return;
        EmailTemplates.Mail mail = EmailTemplates.leadReply(company(e.orgId()), e.text("name"), e.text("listing"), reply, overrideTo.isEmpty() ? null : to);
        db.update("insert into workflow.delivery (id, org_id, notification_id, channel, recipient, subject, body, html, status, note) values (?,?,null,'EMAIL',?,?,?,?,'QUEUED','Waiting to be sent') on conflict (id) do nothing",
                // The id comes from the event, so an event seen twice still gives one email, and answering the same lead again gives another.
                UUID.nameUUIDFromBytes(("lead-email:" + e.id()).getBytes(java.nio.charset.StandardCharsets.UTF_8)), e.orgId(), to, mail.subject(), shorten(mail.text()), mail.html());
    }

    private String company(UUID org) {
        try {
            JsonNode n = client.get(ServiceClient.Service.IDENTITY, "/internal/org-names?ids=" + org);
            String name = n.path(org.toString()).asText("");
            if (!name.isBlank()) return name;
        } catch (RuntimeException ignored) {
            // the email is still worth sending without the company name
        }
        return "Your property manager";
    }

    private static String shorten(String s) {
        return s.length() <= 600 ? s : s.substring(0, 597) + "...";
    }
}
