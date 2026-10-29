package com.propertyos.document.service;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Deadlines;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;

/**
 * Daily document housekeeping: expiry alerts (renters insurance, licences, owner policies), signature reminders and
 * expiry of unsigned requests, and emptying the trash once each file's retention period allows it.
 */
@Service
public class DocumentAlerts {
    private com.propertyos.common.security.SuspendedOrgs suspended;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuspended(com.propertyos.common.security.SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    private final Db db;
    private final ConfigClient config;
    private final Notifier notifier;
    private final DocumentService docs;
    private final SignatureService signatures;
    private final AuditService audit;
    private final PlatformTransactionManager tm;

    public DocumentAlerts(Db db, ConfigClient config, Notifier notifier, DocumentService docs, SignatureService signatures, AuditService audit, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.notifier = notifier;
        this.docs = docs;
        this.signatures = signatures;
        this.audit = audit;
        this.tm = tm;
    }

    private List<Integer> thresholds() {
        return Deadlines.thresholds(config, "alerts/documentExpiry/daysBefore", List.of(7, 14, 30));
    }

    /** Documents that expire soon, plus signature requests close to their due date. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadlines() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (config.flag("alerts/documentExpiry/enabled", true)) {
            int horizon = thresholds().get(thresholds().size() - 1) + 30;
            // Vendor insurance certificates are tracked on the vendor record (maintenance), so they aren't double-counted here.
            for (Map<String, Object> d : db.list("""
                    select id, title, doc_type, expires_on, property_id, tenant_id, owner_id, vendor_id, share_tenant, share_owner, share_vendor, entity_label
                    from document.document where status = 'ACTIVE' and expires_on is not null and expires_on <= current_date + ?::int
                      and not (doc_type = 'Certificate of insurance' and vendor_id is not null) order by expires_on""", horizon)) {
                LocalDate due = LocalDate.parse(String.valueOf(d.get("expires_on")));
                List<String[]> who = new ArrayList<>();
                if (Boolean.TRUE.equals(d.get("share_tenant")) && d.get("tenant_id") != null) who.add(new String[]{"PARTY", d.get("tenant_id").toString()});
                if (Boolean.TRUE.equals(d.get("share_owner")) && d.get("owner_id") != null) who.add(new String[]{"PARTY", d.get("owner_id").toString()});
                if (Boolean.TRUE.equals(d.get("share_vendor")) && d.get("vendor_id") != null) who.add(new String[]{"PARTY", d.get("vendor_id").toString()});
                if (d.get("property_id") != null) who.add(new String[]{"PROPERTY", d.get("property_id").toString()});
                who.add(new String[]{"ROLE", "PROPERTY_MANAGER"});
                String type = String.valueOf(d.get("doc_type") == null ? "Document" : d.get("doc_type"));
                out.add(Deadlines.item("DOCUMENT_EXPIRY", type + " expires: " + d.get("entity_label"), "“" + d.get("title") + "” ends " + due + ". Upload the renewed document.",
                        due, "/documents/" + d.get("id"), String.valueOf(d.get("id")), who));
            }
        }
        for (Map<String, Object> r : db.list("""
                select r.id, r.title, r.due_on, r.created_by, d.id as document_id, d.property_id,
                       (select string_agg(s.name, ', ') from document.signature_signer s where s.request_id = r.id and s.status = 'PENDING') as waiting
                from document.signature_request r join document.document d on d.id = r.document_id
                where r.status = 'PENDING' and r.due_on is not null and r.due_on <= current_date + 7 order by r.due_on""")) {
            LocalDate due = LocalDate.parse(String.valueOf(r.get("due_on")));
            List<String[]> who = new ArrayList<>();
            if (r.get("created_by") != null) who.add(new String[]{"USER", r.get("created_by").toString()});
            out.add(Deadlines.item("SIGNATURE_DUE", "Signature due: " + r.get("title"), "Waiting for " + r.get("waiting") + ".", due, "/documents/" + r.get("document_id"), String.valueOf(r.get("id")), who));
        }
        return out;
    }

    /**
     * Sends the document alerts that are due (expiries and missing requirements).
     *
     * @return counts of what was sent
     */
    @Transactional
    public Map<String, Object> run() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Integer> th = thresholds();
        int sent = 0;
        for (Map<String, Object> d : deadlines())
            if ("DOCUMENT_EXPIRY".equals(d.get("type"))) sent += Deadlines.notifyAll(notifier, d, Deadlines.bucket((Long) d.get("days_left"), th), th);
        out.put("expiryAlerts", sent);

        // Signature reminders, then expire requests that passed their due date.
        int reminded = 0;
        if (config.flag("alerts/signatureReminder/enabled", true)) {
            int every = config.number("alerts/signatureReminder/everyDays", "3").intValue();
            for (Map<String, Object> r : db.list("select id from document.signature_request where status = 'PENDING' and created_at < now() - make_interval(days => ?) and (last_reminded_at is null or last_reminded_at < now() - make_interval(days => ?))", every, every)) {
                signatures.notifyPending((UUID) r.get("id"), true);
                db.update("update document.signature_request set last_reminded_at = now() where id = ?", r.get("id"));
                reminded++;
            }
        }
        out.put("signatureReminders", reminded);
        int expired = 0;
        for (Map<String, Object> r : db.list("select id, document_id, title, created_by from document.signature_request where status = 'PENDING' and due_on < current_date")) {
            db.update("update document.signature_request set status = 'EXPIRED', completed_at = now() where id = ?", r.get("id"));
            db.update("update document.document set signature_status = 'NONE', updated_at = now() where id = ?", r.get("document_id"));
            audit.record("Document", (UUID) r.get("document_id"), "SIGNATURE_EXPIRED", "PENDING", "EXPIRED", Map.of());
            if (r.get("created_by") != null)
                notifier.sendAlert("USER", r.get("created_by").toString(), "Signature request expired", "“" + r.get("title") + "” wasn't signed in time. Send it again if it's still needed.", "/documents/" + r.get("document_id"), "NORMAL", "DOCUMENTS", "sigexp:" + r.get("id"));
            expired++;
        }
        out.put("signatureRequestsExpired", expired);

        // Trash: permanently remove files once they have been in the trash long enough AND their retention period is over.
        int days = config.number("documents/storage/trashDays", "30").intValue();
        int purged = 0;
        for (Map<String, Object> d : db.list("select id, title from document.document where status = 'DELETED' and not legal_hold and deleted_at < now() - make_interval(days => ?) and (retain_until is null or retain_until <= current_date)", days)) {
            docs.purgeRow((UUID) d.get("id"));
            audit.record("Document", (UUID) d.get("id"), "DOCUMENT_PURGED", "DELETED", null, Map.of("title", String.valueOf(d.get("title")), "reason", "Trash emptied automatically"));
            purged++;
        }
        out.put("purged", purged);
        return out;
    }

    /**
     * Daily timer: for each company, sends the document alerts that are due.
     */
    @Scheduled(cron = "0 35 7 * * *", zone = "America/Chicago")
    public void daily() {
        List<UUID> orgs = UserContextHolder.runAsSystem(null, () -> new TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from document.document").stream().map(r -> (UUID) r.get("org_id")).toList()));
        for (UUID org : orgs == null ? List.<UUID>of() : orgs) {
            if (suspended != null && suspended.contains(org)) continue; // a suspended company's reminders are paused
            UserContextHolder.runAsSystem(org, () -> new TransactionTemplate(tm).execute(st -> run()));
        }
    }
}
