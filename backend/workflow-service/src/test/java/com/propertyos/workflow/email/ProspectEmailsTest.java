package com.propertyos.workflow.email;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.rls.Db;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;

class ProspectEmailsTest {
    private static final ObjectMapper M = new ObjectMapper();
    private final EmailQueue queue = Mockito.spy(new EmailQueue(Mockito.mock(Db.class), "", "http://localhost:4173"));
    private final UUID org = UUID.randomUUID();
    private ProspectEmails handler;

    ProspectEmailsTest() throws Exception {
        ServiceClient client = Mockito.mock(ServiceClient.class);
        Mockito.when(client.get(any(ServiceClient.Service.class), anyString())).thenReturn((JsonNode) M.readTree("{\"" + org + "\":\"Summit Ridge\"}"));
        Mockito.doNothing().when(queue).queue(any(), any(), any(), any(), Mockito.anyBoolean(), any(), any());
        handler = new ProspectEmails(queue, new CompanyNames(client));
    }

    private DomainEvent event(String type, String payload, OffsetDateTime at) throws Exception {
        return new DomainEvent(5, org, type, "X", UUID.randomUUID(), M.readTree(payload), null, null, at);
    }

    private EmailTemplates.Mail sent(String to) {
        ArgumentCaptor<EmailTemplates.Mail> mail = ArgumentCaptor.forClass(EmailTemplates.Mail.class);
        Mockito.verify(queue).queue(any(), eq(org), eq(to), mail.capture(), eq(false), any(), any());
        return mail.getValue();
    }

    @Test
    @DisplayName("an application emails the applicant, and its decision emails them again, each once per event")
    void application() throws Exception {
        handler.handle(event("ApplicationSubmitted", "{\"applicantName\":\"Ann Applicant\",\"applicantEmail\":\"ann@example.com\",\"unitLabel\":\"Court 4B\"}", OffsetDateTime.now()));
        assertThat(sent("ann@example.com").subject()).isEqualTo("We received your application");
        Mockito.clearInvocations(queue);
        handler.handle(event("ApplicationDecided", "{\"decision\":\"APPROVED\",\"applicantName\":\"Ann Applicant\",\"applicantEmail\":\"ann@example.com\",\"unitLabel\":\"Court 4B\"}", OffsetDateTime.now()));
        assertThat(sent("ann@example.com").subject()).isEqualTo("Good news about your application");
        Mockito.clearInvocations(queue);
        handler.handle(event("ApplicationDecided", "{\"decision\":\"REJECTED\",\"applicantName\":\"Ann Applicant\",\"applicantEmail\":\"ann@example.com\"}", OffsetDateTime.now()));
        assertThat(sent("ann@example.com").subject()).isEqualTo("An update on your application");
    }

    @Test
    @DisplayName("a tour request and its confirmation email the prospect with the home and the time")
    void tours() throws Exception {
        String p = "{\"prospectName\":\"Tina Tourist\",\"prospectEmail\":\"tina@example.com\",\"listingTitle\":\"Studio 12\",\"at\":\"2026-11-09T20:00:00Z\"}";
        handler.handle(event("ShowingRequested", p, OffsetDateTime.now()));
        assertThat(sent("tina@example.com").html()).contains("Studio 12", "2:00 PM");
        Mockito.clearInvocations(queue);
        handler.handle(event("ShowingConfirmed", p, OffsetDateTime.now()));
        assertThat(sent("tina@example.com").subject()).isEqualTo("Your tour is confirmed");
    }

    @Test
    @DisplayName("an old event, or one with no address, sends nothing, so starting on a long history never emails people about the past")
    void ignored() throws Exception {
        handler.handle(event("ApplicationSubmitted", "{\"applicantName\":\"Old\",\"applicantEmail\":\"old@example.com\"}", OffsetDateTime.now().minusDays(3)));
        handler.handle(event("ApplicationSubmitted", "{\"applicantName\":\"None\"}", OffsetDateTime.now()));
        handler.handle(event("ApplicationSubmitted", "{\"applicantName\":\"Blank\",\"applicantEmail\":\" \"}", OffsetDateTime.now()));
        Mockito.verify(queue, Mockito.never()).queue(any(), any(), any(), any(), Mockito.anyBoolean(), any(), any());
        assertThat(ProspectEmails.recent(event("X", "{}", OffsetDateTime.now().minusHours(7)))).isFalse();
        assertThat(ProspectEmails.recent(event("X", "{}", OffsetDateTime.now().minusHours(5)))).isTrue();
    }

    @Test
    @DisplayName("while emails are redirected to a test address the email says who it was meant for")
    void redirected() throws Exception {
        EmailQueue redirecting = Mockito.spy(new EmailQueue(Mockito.mock(Db.class), "owner@example.com", "http://localhost:4173"));
        Mockito.doNothing().when(redirecting).queue(any(), any(), any(), any(), Mockito.anyBoolean(), any(), any());
        ServiceClient client = Mockito.mock(ServiceClient.class);
        new ProspectEmails(redirecting, new CompanyNames(client)).handle(event("ApplicationSubmitted", "{\"applicantName\":\"Ann\",\"applicantEmail\":\"ann@example.com\"}", OffsetDateTime.now()));
        ArgumentCaptor<EmailTemplates.Mail> mail = ArgumentCaptor.forClass(EmailTemplates.Mail.class);
        Mockito.verify(redirecting).queue(any(), any(), eq("ann@example.com"), mail.capture(), eq(false), any(), any());
        assertThat(mail.getValue().html()).contains("Test mode: this was meant for ann@example.com.");
    }
}
