package com.propertyos.workflow.email;

import com.propertyos.common.rls.Db;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * The one place an email is put in line to be sent. It writes a delivery with the rendered page; {@link EmailDispatcher} sends it, so a slow or failing mail
 * service never holds up the action that caused the email, and a failed send is retried and shown in the delivery log.
 *
 * <p>A <em>sensitive</em> email (one that carries a temporary password) is logged with a neutral summary, and its text is erased as soon as it has been sent.
 * The delivery's id comes from the caller, so an event seen twice still gives one email.
 */
@Component
public class EmailQueue {
    private final Db db;
    private final String overrideTo;
    private final String appUrl;

    public EmailQueue(Db db, @Value("${propertyos.email.override-to:}") String overrideTo, @Value("${propertyos.app-url:http://localhost:4173}") String appUrl) {
        this.db = db;
        this.overrideTo = overrideTo.trim();
        this.appUrl = appUrl.trim().replaceAll("/+$", "");
    }

    /** The real recipient to show in the "test mode" banner while every email is redirected to one address, otherwise null. */
    public String intendedFor(String to) {
        return overrideTo.isEmpty() ? null : to;
    }

    /** A page of the web app, for the button in an email. */
    public String link(String path) {
        if (path == null || path.isBlank()) return null;
        return path.startsWith("http") ? path : appUrl + (path.startsWith("/") ? path : "/" + path);
    }

    /** Queues the email unless an email with this id is already waiting or sent. */
    @Transactional
    public void queue(UUID id, UUID org, String to, EmailTemplates.Mail mail, boolean sensitive, OffsetDateTime sendAfter, String note) {
        if (to == null || to.isBlank() || !to.contains("@")) return;
        String preview = sensitive ? "Sign-in details (not kept after they are sent)" : shorten(mail.text());
        db.update("""
                insert into workflow.delivery (id, org_id, notification_id, channel, recipient, subject, body, html, text_body, sensitive, send_after, status, note)
                values (?,?,null,'EMAIL',?,?,?,?,?,?,coalesce(?, now()),'QUEUED',?) on conflict (id) do nothing""",
                id, org, to.trim(), mail.subject(), preview, mail.html(), mail.text(), sensitive, sendAfter, note == null ? "Waiting to be sent" : note);
    }

    /** The same id for the same thing, so seeing an event twice (or a retry) cannot send two emails. */
    public static UUID idFor(String kind, Object key) {
        return UUID.nameUUIDFromBytes((kind + ":" + key).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String shorten(String s) {
        return s.length() <= 600 ? s : s.substring(0, 597) + "...";
    }
}
