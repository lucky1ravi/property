package com.propertyos.workflow.email;

import com.propertyos.common.rls.Db;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;

class EmailDispatcherTest {
    private final Db db = Mockito.mock(Db.class);
    private final EmailSender sender = Mockito.mock(EmailSender.class);
    private final UUID id = UUID.randomUUID();

    private EmailDispatcher dispatcher(String override, int attempt) throws Exception {
        Mockito.when(sender.name()).thenReturn("ses");
        Mockito.when(db.list(contains("update workflow.delivery set attempts"), any(), any())).thenReturn(List.of(Map.of(
                "id", id, "org_id", UUID.randomUUID(), "recipient", "tessa@example.com", "subject", "Re: your enquiry", "body", "plain", "html", "<p>hi</p>", "attempts", attempt)));
        return new EmailDispatcher(db, sender, Mockito.mock(PlatformTransactionManager.class), override);
    }

    @Test
    @DisplayName("a queued email is sent and marked SENT")
    void sent() throws Exception {
        dispatcher("", 1).tick();
        Mockito.verify(sender).send("tessa@example.com", "Re: your enquiry", "<p>hi</p>", "plain");
        Mockito.verify(db).update(anyString(), eq("SENT"), eq("Sent with Amazon SES"), eq(id));
    }

    @Test
    @DisplayName("while a test address is set, every email goes to it and not to the prospect")
    void redirected() throws Exception {
        dispatcher("owner@example.com", 1).tick();
        Mockito.verify(sender).send(eq("owner@example.com"), anyString(), anyString(), anyString());
        Mockito.verify(sender, Mockito.never()).send(eq("tessa@example.com"), anyString(), anyString(), anyString());
        Mockito.verify(db).update(anyString(), eq("SENT"), eq("Sent with Amazon SES to the test address"), eq(id));
    }

    @Test
    @DisplayName("a failed send stays queued for a retry, and is marked FAILED with the reason after the last try")
    void failures() throws Exception {
        Mockito.doThrow(new RuntimeException("Email address is not verified")).when(sender).send(anyString(), anyString(), anyString(), anyString());
        dispatcher("", 1).tick();
        Mockito.verify(db).update(anyString(), eq("QUEUED"), eq("Try 1 failed, will retry: Email address is not verified"), eq(id));

        Mockito.clearInvocations(db);
        dispatcher("", EmailDispatcher.MAX_ATTEMPTS).tick();
        Mockito.verify(db).update(anyString(), eq("FAILED"), eq("Failed after 3 tries: Email address is not verified"), eq(id));
    }
}
