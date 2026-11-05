package com.propertyos.workflow.email;

import com.propertyos.common.rls.Db;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

class EmailQueueTest {
    private final Db db = Mockito.mock(Db.class);
    private final EmailTemplates.Mail mail = new EmailTemplates.Mail("Subject", "<html>Temp-1</html>", "Your temporary password is Temp-1");

    @Test
    @DisplayName("a sensitive email is logged with a neutral summary, never its text, and is flagged so the text is erased after sending")
    void sensitive() {
        new EmailQueue(db, "", "http://localhost:4173").queue(UUID.randomUUID(), UUID.randomUUID(), "a@example.com", mail, true, null, null);
        ArgumentCaptor<Object> a = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db).update(contains("insert into workflow.delivery"), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture());
        // the log preview (the 5th value) is neutral; the real text is kept only in text_body, which is erased once the email is sent
        assertThat(a.getAllValues().get(4)).isEqualTo("Sign-in details (not kept after they are sent)");
        assertThat(a.getAllValues()).contains(true);
    }

    @Test
    @DisplayName("an ordinary email keeps a short preview of its text in the log")
    void ordinary() {
        new EmailQueue(db, "", "http://localhost:4173").queue(UUID.randomUUID(), UUID.randomUUID(), "a@example.com", mail, false, null, null);
        ArgumentCaptor<Object> a = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(db).update(anyString(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture(), a.capture());
        assertThat(a.getAllValues()).contains("Your temporary password is Temp-1", false);
    }

    @Test
    @DisplayName("nothing is queued for an empty or non-address recipient")
    void noAddress() {
        EmailQueue q = new EmailQueue(db, "", "http://localhost:4173");
        q.queue(UUID.randomUUID(), UUID.randomUUID(), null, mail, false, null, null);
        q.queue(UUID.randomUUID(), UUID.randomUUID(), " ", mail, false, null, null);
        q.queue(UUID.randomUUID(), UUID.randomUUID(), "user-1234", mail, false, null, null);
        Mockito.verify(db, Mockito.never()).update(anyString(), org.mockito.ArgumentMatchers.<Object[]>any());
    }

    @Test
    @DisplayName("links join the app address and a path, and ids are stable for the same thing")
    void links() {
        EmailQueue q = new EmailQueue(db, "owner@example.com", "https://app.example.com//");
        assertThat(q.link("/signin")).isEqualTo("https://app.example.com/signin");
        assertThat(q.link("app/payments")).isEqualTo("https://app.example.com/app/payments");
        assertThat(q.link("https://elsewhere.example.com/x")).isEqualTo("https://elsewhere.example.com/x");
        assertThat(q.link(null)).isNull();
        assertThat(q.intendedFor("a@example.com")).isEqualTo("a@example.com");
        assertThat(new EmailQueue(db, "", "x").intendedFor("a@example.com")).isNull();
        assertThat(EmailQueue.idFor("k", 7)).isEqualTo(EmailQueue.idFor("k", 7)).isNotEqualTo(EmailQueue.idFor("k", 8)).isNotEqualTo(EmailQueue.idFor("j", 7));
    }
}
