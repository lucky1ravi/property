package com.propertyos.workflow.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.rls.Db;
import com.propertyos.workflow.email.CompanyNames;
import com.propertyos.workflow.email.EmailQueue;
import com.propertyos.workflow.email.EmailTemplates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.OffsetDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

class NotificationEmailTest {
    private static final ObjectMapper M = new ObjectMapper();
    private final Db db = Mockito.mock(Db.class);
    private final ServiceClient client = Mockito.mock(ServiceClient.class);
    private final ConfigClient config = Mockito.mock(ConfigClient.class);
    private final EmailQueue emails = Mockito.spy(new EmailQueue(Mockito.mock(Db.class), "", "https://app.example.com"));
    private final UUID org = UUID.randomUUID(), user = UUID.randomUUID();

    private NotificationService service(boolean userKnown) throws Exception {
        Mockito.when(config.flag(anyString(), anyBoolean())).thenReturn(true);
        Mockito.when(config.text(contains("quietHours.start"), anyString())).thenReturn("00:00");
        Mockito.when(config.text(contains("quietHours.end"), anyString())).thenReturn("00:00");
        Mockito.when(client.get(any(ServiceClient.Service.class), contains("/internal/audience"))).thenReturn((JsonNode) M.readTree("[\"" + user + "\"]"));
        if (userKnown) Mockito.when(client.get(any(ServiceClient.Service.class), contains("/internal/users/"))).thenReturn((JsonNode) M.readTree("{\"email\":\"rae@example.com\"}"));
        else Mockito.when(client.get(any(ServiceClient.Service.class), contains("/internal/users/"))).thenThrow(new IllegalStateException("identity is down"));
        Mockito.when(client.get(any(ServiceClient.Service.class), contains("/internal/org-names"))).thenReturn((JsonNode) M.readTree("{\"" + org + "\":\"Summit Ridge\"}"));
        Mockito.doNothing().when(emails).queue(any(), any(), any(), any(), anyBoolean(), any(), any());
        return new NotificationService(db, client, config, emails, new CompanyNames(client));
    }

    private DomainEvent event(String audience, String priority) throws Exception {
        return new DomainEvent(9, org, "NotificationRequested", "Notification", UUID.randomUUID(), M.readTree(
                "{\"audience\":\"" + audience + "\",\"target\":\"" + (audience.equals("ROLE") ? "PROPERTY_MANAGER" : user) + "\",\"title\":\"Payment received\",\"body\":\"We received your payment of $1,850.\","
                        + "\"link\":\"/payments\",\"priority\":\"" + priority + "\",\"category\":\"PAYMENTS\"}"), null, null, OffsetDateTime.now());
    }

    @Test
    @DisplayName("a notification to a resident, owner or vendor is emailed for real, with a button that opens the right page in the app")
    void partyIsEmailed() throws Exception {
        service(true).handle(event("PARTY", "NORMAL"));
        ArgumentCaptor<EmailTemplates.Mail> mail = ArgumentCaptor.forClass(EmailTemplates.Mail.class);
        Mockito.verify(emails).queue(any(), eq(org), eq("rae@example.com"), mail.capture(), eq(false), any(), any());
        assertThat(mail.getValue().subject()).isEqualTo("Payment received");
        assertThat(mail.getValue().html()).contains("We received your payment of $1,850.", "href=\"https://app.example.com/app/payments\"", "Summit Ridge");
    }

    @Test
    @DisplayName("an ordinary notification to staff stays in the inbox, and an urgent one is emailed")
    void staffOnlyWhenUrgent() throws Exception {
        service(true).handle(event("ROLE", "NORMAL"));
        Mockito.verify(emails, Mockito.never()).queue(any(), any(), any(), any(), anyBoolean(), any(), any());
        Mockito.clearInvocations(emails);
        service(true).handle(event("ROLE", "HIGH"));
        Mockito.verify(emails).queue(any(), eq(org), eq("rae@example.com"), any(), eq(false), any(), any());
    }

    @Test
    @DisplayName("when the person's address cannot be found no real email is made up")
    void unknownAddress() throws Exception {
        service(false).handle(event("PARTY", "NORMAL"));
        Mockito.verify(emails, Mockito.never()).queue(any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    @DisplayName("a quiet-hours email is held until the quiet hours end, and that time is in the future")
    void quietHours() {
        OffsetDateTime end = NotificationService.nextQuietEnd("08:00");
        assertThat(end).isNotNull().isAfter(OffsetDateTime.now()).isBefore(OffsetDateTime.now().plusHours(25));
        assertThat(end.atZoneSameInstant(java.time.ZoneId.of("America/Chicago")).toLocalTime()).isEqualTo(java.time.LocalTime.of(8, 0));
        assertThat(NotificationService.nextQuietEnd("not a time")).isNull();
    }
}
