package com.propertyos.identity.service;

import com.propertyos.common.client.ServiceClient;
import com.propertyos.identity.store.IdentityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Asks the workflow service to email a person about their account: a welcome with the one-time password, a reset, and a notice that the password changed.
 * It is a direct call and not an event, because events are kept and these carry a password; the workflow service erases the password once the email is sent.
 * The one-time password is still shown on screen to whoever made or reset the account. An email that cannot be sent never undoes the account change: it is
 * logged, and sent after the change has been saved.
 */
@Component
public class AccountEmails {
    private static final Logger log = LoggerFactory.getLogger(AccountEmails.class);
    private final ServiceClient client;
    private final IdentityStore store;

    public AccountEmails(ServiceClient client, IdentityStore store) {
        this.client = client;
        this.store = store;
    }

    /** A new account, with its one-time password. */
    public void welcome(UUID org, String company, String to, String name, String temporaryPassword) {
        send("WELCOME", org, company, to, name, temporaryPassword);
    }

    /** An administrator reset the password. */
    public void passwordReset(UUID org, String to, String name, String temporaryPassword) {
        send("PASSWORD_RESET", org, null, to, name, temporaryPassword);
    }

    /** The person chose a new password. */
    public void passwordChanged(UUID org, String to, String name) {
        send("PASSWORD_CHANGED", org, null, to, name, null);
    }

    private void send(String kind, UUID org, String company, String to, String name, String password) {
        if (to == null || to.isBlank() || org == null) return;
        Runnable work = () -> {
            try {
                Map<String, Object> body = new HashMap<>();
                body.put("kind", kind);
                body.put("orgId", org.toString());
                body.put("company", company != null ? company : companyName(org));
                body.put("to", to);
                body.put("name", name);
                if (password != null) body.put("password", password);
                client.post(ServiceClient.Service.WORKFLOW, "/internal/email", body);
            } catch (RuntimeException e) {
                log.warn("Could not queue the {} email for {}: {}", kind, to, e.getMessage());
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    work.run();
                }
            });
        } else work.run();
    }

    private String companyName(UUID org) {
        try {
            return store.org(org).map(o -> String.valueOf(o.get("name"))).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
