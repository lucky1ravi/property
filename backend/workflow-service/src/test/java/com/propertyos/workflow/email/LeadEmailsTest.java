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
import static org.mockito.ArgumentMatchers.contains;

class LeadEmailsTest {
    private static final ObjectMapper M = new ObjectMapper();
    private final Db db = Mockito.mock(Db.class);
    private final ServiceClient client = Mockito.mock(ServiceClient.class);
    private final UUID org = UUID.randomUUID();

    private DomainEvent event(long id, String payload) throws Exception {
        return new DomainEvent(id, org, "LeadResponded", "Lead", UUID.randomUUID(), M.readTree(payload), null, null, OffsetDateTime.now());
    }

    private LeadEmails handler(String override) throws Exception {
        JsonNode names = M.readTree("{\"" + org + "\":\"Summit Ridge Property Group\"}");
        Mockito.when(client.get(any(ServiceClient.Service.class), anyString())).thenReturn(names);
        return new LeadEmails(new EmailQueue(db, override, "http://localhost:4173"), new CompanyNames(client));
    }

    @Test
    @DisplayName("an answered lead queues one branded email to the address the prospect gave")
    void queued() throws Exception {
        handler("").handle(event(7, "{\"email\":\"tessa@example.com\",\"name\":\"Tessa Nguyen\",\"listing\":\"2-bedroom\",\"reply\":\"Hi Tessa, welcome.\"}"));
        ArgumentCaptor<Object> args = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db).update(contains("insert into workflow.delivery"), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture(), args.capture());
        assertThat(args.getAllValues()).contains("tessa@example.com", "Re: your enquiry about 2-bedroom");
        assertThat(args.getAllValues().stream().map(String::valueOf).anyMatch(s -> s.contains("Summit Ridge Property Group") && s.contains("<html"))).isTrue();
    }

    @Test
    @DisplayName("the same event gets the same delivery id, so seeing it twice cannot send two emails")
    void sameIdPerEvent() throws Exception {
        LeadEmails h = handler("");
        String p = "{\"email\":\"tessa@example.com\",\"reply\":\"Hello\"}";
        h.handle(event(7, p));
        h.handle(event(7, p));
        h.handle(event(8, p));
        ArgumentCaptor<Object> id = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db, Mockito.times(3)).update(contains("insert into workflow.delivery"), id.capture(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(id.getAllValues().get(0)).isEqualTo(id.getAllValues().get(1)).isNotEqualTo(id.getAllValues().get(2));
    }

    @Test
    @DisplayName("an event with no address or no reply sends nothing")
    void nothingToSend() throws Exception {
        LeadEmails h = handler("");
        h.handle(event(1, "{\"email\":\"\",\"reply\":\"Hello\"}"));
        h.handle(event(2, "{\"email\":\"tessa@example.com\",\"reply\":\"\"}"));
        Mockito.verify(db, Mockito.never()).update(anyString(), any(Object[].class));
    }
}
