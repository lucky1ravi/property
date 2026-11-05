package com.propertyos.identity.service;

import com.propertyos.common.client.ServiceClient;
import com.propertyos.identity.store.IdentityStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

class AccountEmailsTest {
    private final ServiceClient client = Mockito.mock(ServiceClient.class);
    private final IdentityStore store = Mockito.mock(IdentityStore.class);
    private final AccountEmails emails = new AccountEmails(client, store);
    private final UUID org = UUID.randomUUID();

    @SuppressWarnings("unchecked")
    private Map<String, Object> posted() {
        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        Mockito.verify(client).post(eq(ServiceClient.Service.WORKFLOW), eq("/internal/email"), body.capture());
        return (Map<String, Object>) body.getValue();
    }

    @Test
    @DisplayName("a welcome is sent to the workflow service with the company, the person and the one-time password")
    void welcome() {
        emails.welcome(org, "Summit Ridge", "priya@example.com", "Priya Patel", "Temp-1");
        assertThat(posted()).containsEntry("kind", "WELCOME").containsEntry("orgId", org.toString()).containsEntry("company", "Summit Ridge")
                .containsEntry("to", "priya@example.com").containsEntry("name", "Priya Patel").containsEntry("password", "Temp-1");
    }

    @Test
    @DisplayName("a reset names the company from the directory, and a changed-password notice carries no password")
    void resetAndChanged() {
        Mockito.when(store.org(org)).thenReturn(Optional.of(Map.of("name", "Summit Ridge")));
        emails.passwordReset(org, "priya@example.com", "Priya Patel", "Temp-2");
        assertThat(posted()).containsEntry("kind", "PASSWORD_RESET").containsEntry("company", "Summit Ridge").containsEntry("password", "Temp-2");
        Mockito.clearInvocations(client);
        emails.passwordChanged(org, "priya@example.com", "Priya Patel");
        assertThat(posted()).containsEntry("kind", "PASSWORD_CHANGED").doesNotContainKey("password");
    }

    @Test
    @DisplayName("an email that cannot be queued never breaks the account change, and nothing is sent without an address or company")
    void bestEffort() {
        Mockito.when(client.post(any(), any(), any())).thenThrow(new IllegalStateException("workflow is down"));
        emails.welcome(org, "Co", "priya@example.com", "Priya", "Temp-1");   // does not throw
        Mockito.clearInvocations(client);
        emails.welcome(org, "Co", " ", "Priya", "Temp-1");
        emails.welcome(null, "Co", "priya@example.com", "Priya", "Temp-1");
        Mockito.verifyNoInteractions(client);
    }
}
