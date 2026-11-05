package com.propertyos.workflow.email;

import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Turns "a lead was answered" into an email to the prospect (who has no account, so the address they gave is the only way to reach them). The email is only
 * put in line; {@link EmailDispatcher} sends it.
 */
@Component
public class LeadEmails implements EventHandler {
    private final EmailQueue queue;
    private final CompanyNames companies;

    public LeadEmails(EmailQueue queue, CompanyNames companies) {
        this.queue = queue;
        this.companies = companies;
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
        EmailTemplates.Mail mail = EmailTemplates.leadReply(companies.of(e.orgId()), e.text("name"), e.text("listing"), reply, queue.intendedFor(to));
        // The id comes from the event, so an event seen twice still gives one email, and answering the same lead again gives another.
        queue.queue(EmailQueue.idFor("lead-email", e.id()), e.orgId(), to, mail, false, null, null);
    }
}
