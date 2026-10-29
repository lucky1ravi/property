package com.propertyos.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * Files every lease agreement in the document library and keeps its e-signature status in step with leasing:
 * sent for signature, signed by the resident, countersigned. Driven by lease events, with a periodic reconciliation
 * so nothing is missed if an event is delayed (and existing leases are backfilled).
 */
@Component
public class LeaseDocuments implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(LeaseDocuments.class);
    private final Db db;
    private final ServiceClient client;
    private final DocumentService docs;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public LeaseDocuments(Db db, ServiceClient client, DocumentService docs, AuditService audit, PlatformTransactionManager tm) {
        this.db = db;
        this.client = client;
        this.docs = docs;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    @Override
    public String subscriber() {
        return "documents.lease-sync";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LeaseSignatureUpdated", "LeaseActivated", "LeaseRenewed", "LeaseEnded");
    }

    @Override
    public void handle(DomainEvent e) {
        if (e.aggregateId() == null) return;
        JsonNode l = client.get(ServiceClient.Service.LEASING, "/internal/leasing/leases/" + e.aggregateId() + "/brief");
        if (l != null && !l.isEmpty()) sync(l);
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 45_000)
    public void reconcile() {
        JsonNode all;
        try {
            all = UserContextHolder.runAsSystem(null, () -> client.get(ServiceClient.Service.LEASING, "/internal/leasing/lease-documents"));
        } catch (Exception ex) {
            log.debug("Lease reconciliation skipped: {}", ex.getMessage());
            return;
        }
        int changed = 0;
        for (JsonNode l : all) {
            UUID org = UUID.fromString(l.path("org_id").asText());
            try {
                Boolean c = UserContextHolder.runAsSystem(org, () -> tx.execute(s -> sync(l)));
                if (Boolean.TRUE.equals(c)) changed++;
            } catch (Exception ex) {
                log.warn("Lease document sync failed for {}: {}", l.path("id").asText(), ex.getMessage());
            }
        }
        if (changed > 0) log.info("Lease documents reconciled: {} updated", changed);
    }

    static String signatureStatus(String leaseStatus) {
        return switch (leaseStatus) {
            case "SENT" -> "PENDING";
            case "TENANT_SIGNED" -> "PARTIAL";
            case "ACTIVE", "RENEWAL_OFFERED", "NOTICE_GIVEN", "ENDED", "RENEWED" -> "SIGNED";
            default -> null;
        };
    }

    /** Creates or updates the lease agreement document. Returns true when something changed. */
    public boolean sync(JsonNode l) {
        String sig = signatureStatus(l.path("status").asText());
        if (sig == null) return false;
        UUID leaseId = UUID.fromString(l.path("id").asText());
        Optional<Map<String, Object>> existing = db.find("select id, signature_status, current_version, org_id from document.document where entity_type = 'LEASE' and entity_id = ? and doc_type = 'Lease agreement' and source = 'GENERATED' order by created_at limit 1", leaseId);
        if (existing.isPresent() && sig.equals(existing.get().get("signature_status"))) return false;
        byte[] pdf = render(l);
        String unit = l.path("home").asText(l.path("unit_label").asText());
        Lookups.Links links = new Lookups.Links(uuid(l, "property_id"), l.path("property_name").asText(null), uuid(l, "unit_id"), uuid(l, "owner_id"), uuid(l, "tenant_id"), null,
                l.path("label").asText(l.path("tenant_name").asText() + " – " + unit));
        String note = switch (sig) {
            case "PENDING" -> "Sent to the resident for signature";
            case "PARTIAL" -> "Signed by " + l.path("tenant_name").asText() + "; awaiting countersignature";
            default -> "Fully signed";
        };
        String file = "lease-" + unit.replaceAll("[^A-Za-z0-9]+", "-").toLowerCase() + ".pdf";
        if (existing.isEmpty()) {
            UUID id = docs.insertGenerated(UUID.fromString(l.path("org_id").asText()), "Lease agreement – " + unit, "LEASE", "Lease agreement", "LEASE", leaseId, links,
                    true, true, false, file, pdf, sig, note);
            if (!l.path("end_date").asText("").isBlank())
                db.update("update document.document set retain_until = (?::date + interval '7 years')::date where id = ?", l.path("end_date").asText(), id);
            audit.record("Document", id, "LEASE_FILED", null, sig, Map.of("lease", leaseId.toString()));
        } else {
            Map<String, Object> d = existing.get();
            UUID id = (UUID) d.get("id");
            int v = ((Number) d.get("current_version")).intValue() + 1;
            FileRules.Checked f = new FileRules.Checked(file, "pdf", "application/pdf", pdf.length, FileRules.sha256(pdf));
            docs.addVersion(id, (UUID) d.get("org_id"), v, f, pdf, note, null, "PropertyOS");
            db.update("update document.document set current_version = ?, file_name = ?, size_bytes = ?, sha256 = ?, signature_status = ?, updated_at = now() where id = ?",
                    v, file, f.size(), f.sha256(), sig, id);
            audit.record("Document", id, "LEASE_SIGNATURE_UPDATED", (String) d.get("signature_status"), sig, Map.of("version", v));
        }
        return true;
    }

    private static UUID uuid(JsonNode n, String f) {
        return Lookups.uuid(n, f);
    }

    static byte[] render(JsonNode l) {
        String home = l.path("home").asText(l.path("unit_label").asText());
        Pdf pdf = new Pdf("Residential lease – " + home);
        pdf.title("Residential Lease Agreement");
        pdf.text(home + (l.path("property_address").asText("").isBlank() ? "" : ", " + l.path("property_address").asText())).space();
        pdf.text(l.path("document_text").asText("(Lease text not available)"));
        pdf.space().heading("Signatures");
        String ts = l.path("tenant_signature").asText("");
        pdf.field("Resident", ts.isBlank() ? "Not signed yet" : "/s/ " + ts + "   signed " + l.path("tenant_signed_at").asText("") +
                (l.path("tenant_sign_ip").asText("").isBlank() ? "" : "   from " + l.path("tenant_sign_ip").asText()));
        String ms = l.path("manager_signature").asText("");
        pdf.field("Landlord or agent", ms.isBlank() ? "Not signed yet" : "/s/ " + ms + "   signed " + l.path("manager_signed_at").asText(""));
        pdf.space().text("Signed electronically in PropertyOS. Each party typed their legal name and consented to sign electronically.");
        return pdf.bytes();
    }
}
