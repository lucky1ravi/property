package com.propertyos.workflow.email;

import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Service-to-service (internal network and service token only): the identity service asks for an account email to be sent. It is a call, not an event, because
 * an event is kept in the event log and these emails carry a one-time password. The email is put in line like any other and the secret is erased once it is sent.
 */
@RestController
@RequestMapping("/internal/email")
public class InternalEmailController {
    private final EmailQueue queue;

    public InternalEmailController(EmailQueue queue) {
        this.queue = queue;
    }

    /**
     * Body: kind (WELCOME, PASSWORD_RESET or PASSWORD_CHANGED), orgId, company, to, name, and for the first two, password. The sign-in link is added here.
     */
    @PostMapping
    public Map<String, Object> send(@RequestBody Map<String, Object> b) {
        String kind = text(b, "kind"), to = text(b, "to"), company = text(b, "company"), name = text(b, "name"), password = text(b, "password");
        UUID org = UUID.fromString(text(b, "orgId"));
        if (to == null || !to.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Give a valid email address.");
        String company0 = company == null ? CompanyNames.FALLBACK : company;
        String signIn = queue.link("/signin"), intended = queue.intendedFor(to);
        boolean secret = false;
        EmailTemplates.Mail mail;
        switch (kind == null ? "" : kind) {
            case "WELCOME" -> {
                if (password == null) throw ApiException.badRequest("A welcome email needs the temporary password.");
                mail = EmailTemplates.welcome(company0, name, to, password, signIn, intended);
                secret = true;
            }
            case "PASSWORD_RESET" -> {
                if (password == null) throw ApiException.badRequest("A reset email needs the temporary password.");
                mail = EmailTemplates.passwordReset(company0, name, to, password, signIn, intended);
                secret = true;
            }
            case "PASSWORD_CHANGED" -> mail = EmailTemplates.passwordChanged(company0, name, signIn, intended);
            default -> throw ApiException.badRequest("Unknown email kind.");
        }
        EmailTemplates.Mail m = mail;
        boolean sensitive = secret;
        UserContextHolder.runAsSystem(org, () -> { queue.queue(UUID.randomUUID(), org, to, m, sensitive, null, null); return null; });
        return Map.of("queued", true);
    }

    private static String text(Map<String, Object> b, String key) {
        Object v = b.get(key);
        return v == null || String.valueOf(v).isBlank() ? null : String.valueOf(v);
    }
}
