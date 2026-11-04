package com.propertyos.workflow.email;

import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends the queued emails that carry a rendered page. A send that fails is tried again on the next ticks, up to {@link #MAX_ATTEMPTS} times in all, then marked
 * FAILED with the reason, which the delivery log shows. A claim moves a row's attempt count first, so two servers never send the same email.
 */
@Component
public class EmailDispatcher {
    private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);
    static final int MAX_ATTEMPTS = 3;
    private static final int BATCH = 10;

    private final Db db;
    private final EmailSender sender;
    private final TransactionTemplate tx;
    private final String overrideTo;

    public EmailDispatcher(Db db, EmailSender sender, PlatformTransactionManager tm, @Value("${propertyos.email.override-to:}") String overrideTo) {
        this.db = db;
        this.sender = sender;
        this.tx = new TransactionTemplate(tm);
        this.overrideTo = overrideTo.trim();
    }

    @Scheduled(fixedDelayString = "${propertyos.email.poll-ms:5000}", initialDelay = 10_000)
    public void tick() {
        try {
            List<Map<String, Object>> due = UserContextHolder.runAsSystem(null, () -> tx.execute(st -> db.list("""
                    update workflow.delivery set attempts = attempts + 1
                    where id in (select id from workflow.delivery where channel = 'EMAIL' and status = 'QUEUED' and html is not null and attempts < ?
                                 order by created_at limit ? for update skip locked)
                    returning id, org_id, recipient, subject, body, html, attempts""", MAX_ATTEMPTS, BATCH)));
            if (due != null) for (Map<String, Object> d : due) deliver(d);
        } catch (RuntimeException e) {
            log.warn("Email timer failed: {}", e.getMessage());
        }
    }

    private void deliver(Map<String, Object> d) {
        UUID id = (UUID) d.get("id"), org = (UUID) d.get("org_id");
        int attempts = ((Number) d.get("attempts")).intValue();
        String to = overrideTo.isEmpty() ? (String) d.get("recipient") : overrideTo;
        String status, note;
        try {
            sender.send(to, (String) d.get("subject"), (String) d.get("html"), (String) d.get("body"));
            status = "SENT";
            note = sender.name().equals("ses") ? "Sent with Amazon SES" + (overrideTo.isEmpty() ? "" : " to the test address") : "Simulated delivery (nothing was sent)";
        } catch (Exception e) {
            boolean last = attempts >= MAX_ATTEMPTS;
            status = last ? "FAILED" : "QUEUED";
            note = clip((last ? "Failed after " + attempts + " tries: " : "Try " + attempts + " failed, will retry: ") + e.getMessage());
            log.warn("Email {} to {} failed (try {}): {}", id, to, attempts, e.getMessage());
        }
        String s = status, n = note;
        UserContextHolder.runAsSystem(org, () -> tx.execute(st -> db.update("update workflow.delivery set status = ?, note = ? where id = ?", s, n, id)));
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replaceAll("\\s+", " ");
        return t.length() <= 200 ? t : t.substring(0, 197) + "...";
    }
}
