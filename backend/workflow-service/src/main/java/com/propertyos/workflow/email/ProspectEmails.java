package com.propertyos.workflow.email;

import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;

/**
 * Emails to people who have no account: an applicant is told their application arrived and what was decided, and a prospect is told their tour request arrived
 * and when it is confirmed. The address comes from the event. Events older than a few hours are ignored, so starting this handler on a platform with a long
 * history never emails people about things that happened long ago.
 */
@Component
public class ProspectEmails implements EventHandler {
    static final Duration FRESH = Duration.ofHours(6);
    private final EmailQueue queue;
    private final CompanyNames companies;

    public ProspectEmails(EmailQueue queue, CompanyNames companies) {
        this.queue = queue;
        this.companies = companies;
    }

    @Override
    public String subscriber() {
        return "workflow.prospect-emails";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ApplicationSubmitted", "ApplicationDecided", "ShowingRequested", "ShowingConfirmed");
    }

    static boolean recent(DomainEvent e) {
        return e.createdAt() == null || e.createdAt().isAfter(OffsetDateTime.now().minus(FRESH));
    }

    @Override
    @Transactional
    public void handle(DomainEvent e) {
        if (!recent(e)) return;
        String company = companies.of(e.orgId());
        switch (e.type()) {
            case "ApplicationSubmitted" -> send(e, e.text("applicantEmail"), "application-received", email -> EmailTemplates.applicationReceived(company, e.text("applicantName"), e.text("unitLabel"), queue.intendedFor(email)));
            case "ApplicationDecided" -> send(e, e.text("applicantEmail"), "application-decision", email ->
                    EmailTemplates.applicationDecision(company, e.text("applicantName"), e.text("unitLabel"), "APPROVED".equals(e.text("decision")), queue.intendedFor(email)));
            case "ShowingRequested" -> send(e, e.text("prospectEmail"), "tour-requested", email -> EmailTemplates.tour(company, e.text("prospectName"), e.text("listingTitle"), at(e), false, queue.intendedFor(email)));
            case "ShowingConfirmed" -> send(e, e.text("prospectEmail"), "tour-confirmed", email -> EmailTemplates.tour(company, e.text("prospectName"), e.text("listingTitle"), at(e), true, queue.intendedFor(email)));
            default -> { }
        }
    }

    private void send(DomainEvent e, String to, String kind, java.util.function.Function<String, EmailTemplates.Mail> build) {
        if (to == null || to.isBlank()) return;
        // A decision is told once per application: an application the owner must also approve is decided twice, and the applicant needs one email.
        Object key = "ApplicationDecided".equals(e.type()) ? e.aggregateId() + ":" + e.text("decision") : e.id();
        queue.queue(EmailQueue.idFor(kind, key), e.orgId(), to, build.apply(to), false, null, null);
    }

    private static OffsetDateTime at(DomainEvent e) {
        try {
            return e.text("at") == null ? null : OffsetDateTime.parse(e.text("at"));
        } catch (java.time.format.DateTimeParseException ex) {
            return null;
        }
    }
}
