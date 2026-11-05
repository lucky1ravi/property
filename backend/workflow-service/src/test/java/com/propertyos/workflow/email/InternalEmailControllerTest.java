package com.propertyos.workflow.email;

import com.propertyos.common.rls.Db;
import com.propertyos.common.web.ApiException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class InternalEmailControllerTest {
    private final EmailQueue queue = Mockito.spy(new EmailQueue(Mockito.mock(Db.class), "", "https://app.example.com/"));
    private final InternalEmailController controller = new InternalEmailController(queue);
    private final UUID org = UUID.randomUUID();

    InternalEmailControllerTest() {
        Mockito.doNothing().when(queue).queue(any(), any(), any(), any(), Mockito.anyBoolean(), any(), any());
    }

    private Map<String, Object> body(String kind, String password) {
        Map<String, Object> b = new HashMap<>();
        b.put("kind", kind);
        b.put("orgId", org.toString());
        b.put("company", "Summit Ridge");
        b.put("to", "priya@example.com");
        b.put("name", "Priya Patel");
        if (password != null) b.put("password", password);
        return b;
    }

    @Test
    @DisplayName("a welcome and a reset carry the password and are queued as sensitive, so the text is erased once sent; a changed-password notice is not")
    void kinds() {
        assertThat(controller.send(body("WELCOME", "Temp-1"))).containsEntry("queued", true);
        ArgumentCaptor<EmailTemplates.Mail> mail = ArgumentCaptor.forClass(EmailTemplates.Mail.class);
        Mockito.verify(queue).queue(any(), eq(org), eq("priya@example.com"), mail.capture(), eq(true), any(), any());
        assertThat(mail.getValue().html()).contains("Temp-1", "href=\"https://app.example.com/signin\"");
        Mockito.clearInvocations(queue);
        controller.send(body("PASSWORD_RESET", "Temp-2"));
        Mockito.verify(queue).queue(any(), eq(org), any(), any(), eq(true), any(), any());
        Mockito.clearInvocations(queue);
        controller.send(body("PASSWORD_CHANGED", null));
        Mockito.verify(queue).queue(any(), eq(org), any(), any(), eq(false), any(), any());
    }

    @Test
    @DisplayName("a bad address, an unknown kind or a missing password is refused and nothing is queued")
    void refused() {
        Map<String, Object> bad = body("WELCOME", "Temp-1");
        bad.put("to", "not-an-address");
        assertThatThrownBy(() -> controller.send(bad)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.send(body("SURPRISE", "x"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.send(body("WELCOME", null))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> controller.send(body("PASSWORD_RESET", null))).isInstanceOf(ApiException.class);
        Mockito.verify(queue, Mockito.never()).queue(any(), any(), any(), any(), Mockito.anyBoolean(), any(), any());
    }
}
