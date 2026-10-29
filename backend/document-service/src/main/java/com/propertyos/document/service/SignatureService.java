package com.propertyos.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Electronic signatures for any document: request signatures from residents, owners, vendors or staff, in order or in
 * parallel. Each signer types their legal name and consents to sign electronically; the platform records time, IP and
 * device, checks the file has not changed since it was sent (SHA-256), and issues a certificate of completion.
 */
@Service
public class SignatureService {
    private final Db db;
    private final ConfigClient config;
    private final DocumentService docs;
    private final Lookups lookups;
    private final AuditService audit;
    private final EventPublisher events;
    private final Notifier notifier;

    public SignatureService(Db db, ConfigClient config, DocumentService docs, Lookups lookups, AuditService audit, EventPublisher events, Notifier notifier) {
        this.db = db;
        this.config = config;
        this.docs = docs;
        this.lookups = lookups;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
    }

    private void enabled() {
        if (!config.featureEnabled("ESIGN")) throw ApiException.forbidden("E-signatures aren't included in your plan.");
    }

    /** Everyone on a request, whoever is asking: a signer must see who else signs and in what order. */
    private List<Map<String, Object>> signersOf(String sql, Object... args) {
        return docs.elevated(() -> db.list(sql, args));
    }

    /**
     * Lists people who may be asked to sign a document, with how each relates to it.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> candidates(UUID documentId) {
        Map<String, Object> d = docs.load(documentId);
        Set<String> parties = new HashSet<>();
        for (String k : List.of("tenant_id", "owner_id", "vendor_id")) if (d.get(k) != null) parties.add(d.get(k).toString());
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode u : lookups.directory()) {
            String partyType = u.path("party_type").asText("");
            String partyId = u.path("party_id").asText("");
            String reason;
            if (partyType.isBlank() || "null".equals(partyType)) {
                String roles = u.path("roles").asText("");
                if (roles.contains("PLATFORM")) continue;
                reason = "Staff";
            } else if (parties.contains(partyId)) {
                reason = switch (partyType) { case "TENANT" -> "Resident"; case "OWNER" -> "Owner"; case "VENDOR" -> "Vendor"; default -> partyType; };
            } else continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("userId", u.path("id").asText());
            m.put("name", u.path("name").asText());
            m.put("title", u.path("title").asText(""));
            m.put("partyType", partyType.isBlank() || "null".equals(partyType) ? null : partyType);
            m.put("partyId", partyId.isBlank() || "null".equals(partyId) ? null : partyId);
            m.put("relationship", reason);
            out.add(m);
        }
        out.sort(Comparator.comparing((Map<String, Object> m) -> "Staff".equals(m.get("relationship")) ? 1 : 0).thenComparing(m -> String.valueOf(m.get("name"))));
        return out;
    }

    /**
     * Sends a document for signature.
     *
     * @param documentId the document
     * @param b           signers and an optional message
     * @throws ApiException (403) without permission, (409) unless the document is active and not already out for signature, (400) unless it is a PDF with at least one signer
     */
    @Transactional
    @SuppressWarnings("unchecked")
    public Map<String, Object> create(UUID documentId, Map<String, Object> b) {
        enabled();
        UserContext ctx = UserContextHolder.require();
        if (!DocumentService.staff(ctx) || !ctx.has("ESIGN:CREATE")) throw ApiException.forbidden("You can't send documents for signature.");
        Map<String, Object> d = docs.load(documentId);
        if (!"ACTIVE".equals(d.get("status"))) throw ApiException.conflict("Only active documents can be sent for signature.");
        if (List.of("PENDING", "PARTIAL").contains(d.get("signature_status"))) throw ApiException.conflict("This document is already out for signature.");
        if (!"application/pdf".equals(d.get("mime_type"))) throw ApiException.badRequest("Send PDF files for signature so every signer sees exactly the same pages.");
        List<Object> requested = b.get("signers") instanceof List<?> l ? (List<Object>) l : List.of();
        if (requested.isEmpty()) throw ApiException.badRequest("Add at least one signer.");
        Map<String, Map<String, Object>> allowed = new HashMap<>();
        for (Map<String, Object> c : candidates(documentId)) allowed.put(String.valueOf(c.get("userId")), c);
        UUID id = UUID.randomUUID();
        boolean sequential = Boolean.TRUE.equals(b.get("sequential"));
        int days = config.number("documents/esign/expiryDays", "14").intValue();
        LocalDate due = b.get("dueOn") == null || String.valueOf(b.get("dueOn")).isBlank() ? LocalDate.now().plusDays(days) : LocalDate.parse(String.valueOf(b.get("dueOn")));
        String title = b.get("title") == null || String.valueOf(b.get("title")).isBlank() ? "Please sign: " + d.get("title") : String.valueOf(b.get("title"));
        db.update("insert into document.signature_request (id, org_id, document_id, property_id, title, message, sequential, document_sha256, due_on, created_by, created_by_name) values (?,?,?,?,?,?,?,?,?,?,?)",
                id, ctx.orgId(), documentId, d.get("property_id"), DocumentService.trim(title, 200), DocumentService.trim(DocumentService.str(b.get("message")), 1000), sequential, d.get("sha256"), due, ctx.userId(), ctx.name());
        int order = 1;
        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object o : requested) {
            String uid = o instanceof Map<?, ?> m ? String.valueOf(m.get("userId")) : String.valueOf(o);
            Map<String, Object> c = allowed.get(uid);
            if (c == null) throw ApiException.badRequest("One of the signers isn't linked to this document.");
            if (!seen.add(uid)) continue;
            String role = o instanceof Map<?, ?> m && m.get("role") != null ? String.valueOf(m.get("role")) : String.valueOf(c.get("relationship"));
            db.update("insert into document.signature_signer (id, request_id, document_id, org_id, signer_order, user_id, party_type, party_id, name, role_label) values (?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), id, documentId, ctx.orgId(), order++, UUID.fromString(uid), c.get("partyType"),
                    c.get("partyId") == null ? null : UUID.fromString(String.valueOf(c.get("partyId"))), c.get("name"), DocumentService.trim(role, 60));
            names.add(String.valueOf(c.get("name")));
        }
        db.update("update document.document set signature_status = 'PENDING', updated_at = now() where id = ?", documentId);
        audit.record("Document", documentId, "SIGNATURE_REQUESTED", null, "PENDING", Map.of("signers", String.join(", ", names), "due", due.toString(), "sha256", String.valueOf(d.get("sha256"))));
        events.publish("SignatureRequested", "SignatureRequest", id, Map.of("documentId", documentId.toString(), "signers", names.size()));
        notifyPending(id, false);
        return get(id);
    }

    /** Tells the signers whose turn it is. */
    void notifyPending(UUID requestId, boolean reminder) {
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", requestId);
        List<Map<String, Object>> signers = signersOf("select * from document.signature_signer where request_id = ? order by signer_order", requestId);
        Integer next = signers.stream().filter(s -> "PENDING".equals(s.get("status"))).map(s -> ((Number) s.get("signer_order")).intValue()).min(Integer::compare).orElse(null);
        for (Map<String, Object> s : signers) {
            if (!"PENDING".equals(s.get("status")) || s.get("user_id") == null) continue;
            if (Boolean.TRUE.equals(r.get("sequential")) && ((Number) s.get("signer_order")).intValue() != next) continue;
            String t = reminder ? "Reminder: document waiting for your signature" : "Document ready for your signature";
            String body = r.get("created_by_name") + " asked you to sign “" + r.get("title") + "” by " + r.get("due_on") + ".";
            notifier.sendAlert("USER", s.get("user_id").toString(), t, body, "/documents/sign/" + requestId, "HIGH", "DOCUMENTS",
                    reminder ? "sigrem:" + requestId + ":" + s.get("id") + ":" + LocalDate.now() : null);
        }
    }

    /**
     * Lists signature requests.
     *
     * @param mine   true for only those waiting for the signed-in user
     * @param status status filter, or null
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(boolean mine, String status) {
        UserContext ctx = UserContextHolder.require();
        StringBuilder sql = new StringBuilder("""
                select r.id, r.document_id, r.title, r.message, r.status, r.sequential, r.due_on, r.created_by_name, r.created_at, r.completed_at, r.certificate_id,
                       d.title as document_title, d.entity_label, d.property_name, d.category
                from document.signature_request r join document.document d on d.id = r.document_id where 1=1""");
        List<Object> args = new ArrayList<>();
        if (mine || !DocumentService.staff(ctx)) {
            sql.append(" and exists (select 1 from document.signature_signer s where s.request_id = r.id and (s.user_id = ? or s.party_id::text = ?))");
            args.add(ctx.userId());
            args.add(ctx.partyId() == null ? "-" : ctx.partyId().toString());
        }
        if (status != null && !status.isBlank()) { sql.append(" and r.status = ?"); args.add(status); }
        sql.append(" order by case r.status when 'PENDING' then 0 else 1 end, r.created_at desc limit 300");
        List<Map<String, Object>> rows = db.list(sql.toString(), args.toArray());
        for (Map<String, Object> r : rows) {
            List<Map<String, Object>> signers = signersOf("select id, signer_order, name, role_label, status, signed_at, viewed_at, user_id from document.signature_signer where request_id = ? order by signer_order", r.get("id"));
            r.put("signers", signers);
            r.put("signed", signers.stream().filter(s -> "SIGNED".equals(s.get("status"))).count());
            Map<String, Object> me = signers.stream().filter(s -> ctx.userId() != null && ctx.userId().equals(s.get("user_id"))).findFirst().orElse(null);
            r.put("myStatus", me == null ? null : me.get("status"));
            r.put("myTurn", me != null && "PENDING".equals(me.get("status")) && "PENDING".equals(r.get("status")) && isTurn(r, signers, me));
        }
        return rows;
    }

    private static boolean isTurn(Map<String, Object> r, List<Map<String, Object>> signers, Map<String, Object> me) {
        if (!Boolean.TRUE.equals(r.get("sequential"))) return true;
        int mine = ((Number) me.get("signer_order")).intValue();
        return signers.stream().noneMatch(s -> ((Number) s.get("signer_order")).intValue() < mine && !"SIGNED".equals(s.get("status")));
    }

    /**
     * Returns one signature request with each signer's status.
     */
    @Transactional
    public Map<String, Object> get(UUID id) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", id);
        r.remove("org_id");
        List<Map<String, Object>> signers = signersOf("select id, signer_order, name, role_label, party_type, status, viewed_at, signed_at, decline_reason, user_id from document.signature_signer where request_id = ? order by signer_order", id);
        Map<String, Object> me = signers.stream().filter(s -> ctx.userId() != null && ctx.userId().equals(s.get("user_id"))).findFirst().orElse(null);
        if (me == null && !DocumentService.staff(ctx)) throw ApiException.notFound("Signature request");
        if (me != null && me.get("viewed_at") == null) {
            db.update("update document.signature_signer set viewed_at = now() where id = ?", me.get("id"));
            audit.record("Document", (UUID) r.get("document_id"), "SIGNATURE_VIEWED", null, null, Map.of("signer", String.valueOf(me.get("name"))));
        }
        Map<String, Object> d = db.one("Document", "select id, title, file_name, mime_type, size_bytes, sha256, signature_status, entity_label, property_name, category from document.document where id = ?", r.get("document_id"));
        r.put("document", d);
        r.put("signers", signers);
        r.put("me", me);
        r.put("myTurn", me != null && "PENDING".equals(me.get("status")) && "PENDING".equals(r.get("status")) && isTurn(r, signers, me));
        r.put("consentText", config.text("documents/esign/consentText", "I agree to sign electronically."));
        r.put("unchanged", Objects.equals(d.get("sha256"), r.get("document_sha256")));
        r.put("canManage", DocumentService.staff(ctx) && ctx.has("ESIGN:CREATE"));
        return r;
    }

    /**
     * Records a signer's signature (typed name, consent, IP address and browser) respecting the signing order, and writes the document's hash to the audit trail.
     *
     * @param id        the signature request
     * @param typedName the signer's typed name
     * @param consent   must be true
     * @param ip        caller address, kept as evidence
     * @param userAgent caller's browser, kept as evidence
     */
    @Transactional
    public Map<String, Object> sign(UUID id, String typedName, boolean consent, String ip, String userAgent) {
        enabled();
        UserContext ctx = UserContextHolder.require();
        if (!ctx.has("ESIGN:SIGN")) throw ApiException.forbidden("Your role can't sign documents.");
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", id);
        if (!"PENDING".equals(r.get("status"))) throw ApiException.conflict("This request is " + String.valueOf(r.get("status")).toLowerCase() + ".");
        if (r.get("due_on") != null && LocalDate.parse(r.get("due_on").toString()).isBefore(LocalDate.now())) throw ApiException.conflict("This request expired on " + r.get("due_on") + ". Ask the sender for a new one.");
        List<Map<String, Object>> signers = signersOf("select * from document.signature_signer where request_id = ? order by signer_order", id);
        Map<String, Object> me = signers.stream().filter(s -> ctx.userId().equals(s.get("user_id"))).findFirst().orElseThrow(() -> ApiException.forbidden("You aren't a signer on this document."));
        if (!"PENDING".equals(me.get("status"))) throw ApiException.conflict("You've already responded to this request.");
        if (!isTurn(r, signers, me)) throw ApiException.conflict("Another person signs before you. You'll be notified when it's your turn.");
        if (config.flag("documents/esign/requireConsent", true) && !consent) throw ApiException.badRequest("Agree to sign electronically to continue.");
        String expected = String.valueOf(me.get("name")).trim();
        if (typedName == null || !typedName.trim().replaceAll("\\s+", " ").equalsIgnoreCase(expected.replaceAll("\\s+", " ")))
            throw ApiException.badRequest("Type your full legal name exactly as shown: " + expected);
        Map<String, Object> d = db.one("Document", "select id, title, sha256, category, doc_type, entity_type, entity_id, entity_label, property_id, property_name, unit_id, owner_id, tenant_id, vendor_id, share_owner, share_tenant, share_vendor, org_id, uploaded_by from document.document where id = ?", r.get("document_id"));
        if (!Objects.equals(d.get("sha256"), r.get("document_sha256"))) throw ApiException.conflict("The document changed after it was sent for signature. The sender needs to send it again.");
        db.update("update document.signature_signer set status = 'SIGNED', signed_at = now(), typed_signature = ?, consent = ?, ip_address = ?, user_agent = ?, viewed_at = coalesce(viewed_at, now()) where id = ?",
                DocumentService.trim(typedName.trim(), 160), consent, DocumentService.trim(ip, 60), DocumentService.trim(userAgent, 300), me.get("id"));
        audit.record("Document", (UUID) d.get("id"), "DOCUMENT_SIGNED", null, null, Map.of("signer", expected, "ip", String.valueOf(ip), "sha256", String.valueOf(d.get("sha256"))));
        long left = signers.stream().filter(s -> !s.get("id").equals(me.get("id")) && !"SIGNED".equals(s.get("status"))).count();
        if (left == 0) docs.elevated(() -> { complete(r, d); return null; });
        else {
            db.update("update document.document set signature_status = 'PARTIAL', updated_at = now() where id = ?", d.get("id"));
            if (r.get("created_by") != null)
                notifier.sendAlert("USER", r.get("created_by").toString(), "Signed by " + expected, "“" + d.get("title") + "”: " + left + " more signature" + (left == 1 ? "" : "s") + " to go.", "/documents/" + d.get("id"), "NORMAL", "DOCUMENTS", null);
            if (Boolean.TRUE.equals(r.get("sequential"))) notifyPending(id, false);
        }
        return get(id);
    }

    private void complete(Map<String, Object> r, Map<String, Object> d) {
        UUID id = (UUID) r.get("id");
        List<Map<String, Object>> signers = db.list("select * from document.signature_signer where request_id = ? order by signer_order", id);
        DateTimeFormatter f = DateTimeFormatter.ofPattern("MMM d, yyyy 'at' HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC);
        Pdf pdf = new Pdf("Certificate of completion – " + d.get("title"));
        pdf.title("Certificate of completion");
        pdf.text("Electronic signature record issued by PropertyOS.").space();
        pdf.heading("Document");
        pdf.field("Title", String.valueOf(d.get("title")));
        pdf.field("Document ID", String.valueOf(d.get("id")));
        pdf.field("Linked to", String.valueOf(d.get("entity_label")));
        pdf.field("SHA-256 fingerprint", String.valueOf(d.get("sha256")));
        pdf.heading("Request");
        pdf.field("Sent by", String.valueOf(r.get("created_by_name")));
        pdf.field("Sent", f.format(((java.sql.Timestamp) toTs(r.get("created_at"))).toInstant()));
        pdf.field("Signing order", Boolean.TRUE.equals(r.get("sequential")) ? "In order" : "Any order");
        pdf.field("Completed", f.format(java.time.Instant.now()));
        pdf.heading("Signers");
        for (Map<String, Object> s : signers) {
            pdf.space();
            pdf.field("Name", String.valueOf(s.get("name")) + (s.get("role_label") == null ? "" : " (" + s.get("role_label") + ")"));
            pdf.field("Signature", s.get("typed_signature") == null ? "Signed now" : "/s/ " + s.get("typed_signature"));
            Object at = s.get("signed_at");
            pdf.field("Signed", at == null ? f.format(java.time.Instant.now()) : f.format(((java.sql.Timestamp) toTs(at)).toInstant()));
            pdf.field("IP address", String.valueOf(s.get("ip_address")));
            pdf.field("Device", String.valueOf(s.get("user_agent")));
            pdf.field("Consent to electronic signature", Boolean.TRUE.equals(s.get("consent")) ? "Given" : "Not required");
        }
        pdf.space().heading("Consent wording shown to every signer");
        pdf.text(config.text("documents/esign/consentText", "I agree to sign electronically."));
        pdf.space().text("Any change to the signed file changes its SHA-256 fingerprint, so this certificate also proves the file was not altered after signing.");
        Lookups.Links links = new Lookups.Links((UUID) d.get("property_id"), (String) d.get("property_name"), (UUID) d.get("unit_id"), (UUID) d.get("owner_id"),
                (UUID) d.get("tenant_id"), (UUID) d.get("vendor_id"), (String) d.get("entity_label"));
        boolean st = Boolean.TRUE.equals(d.get("share_tenant")) || signers.stream().anyMatch(s -> "TENANT".equals(s.get("party_type")));
        boolean so = Boolean.TRUE.equals(d.get("share_owner")) || signers.stream().anyMatch(s -> "OWNER".equals(s.get("party_type")));
        boolean sv = Boolean.TRUE.equals(d.get("share_vendor")) || signers.stream().anyMatch(s -> "VENDOR".equals(s.get("party_type")));
        String safe = String.valueOf(d.get("title")).replaceAll("[^A-Za-z0-9 -]", "").trim().replace(' ', '-');
        UUID cert = docs.insertGenerated((UUID) d.get("org_id"), "Signature certificate – " + d.get("title"), (String) d.get("category"), "Signature certificate",
                (String) d.get("entity_type"), (UUID) d.get("entity_id"), links, so, st, sv, "certificate-" + (safe.isEmpty() ? "document" : safe) + ".pdf", pdf.bytes(), "SIGNED", "Issued when all parties signed");
        db.update("update document.signature_request set status = 'COMPLETED', completed_at = now(), certificate_id = ? where id = ?", cert, id);
        db.update("update document.document set signature_status = 'SIGNED', updated_at = now() where id = ?", d.get("id"));
        audit.record("Document", (UUID) d.get("id"), "SIGNATURE_COMPLETED", "PENDING", "SIGNED", Map.of("certificate", cert.toString()));
        events.publish("DocumentSigned", "Document", (UUID) d.get("id"), Map.of("requestId", id.toString(), "certificateId", cert.toString()));
        if (r.get("created_by") != null)
            notifier.sendAlert("USER", r.get("created_by").toString(), "Fully signed: " + d.get("title"), "Everyone has signed. The certificate of completion is filed with the document.", "/documents/" + d.get("id"), "NORMAL", "DOCUMENTS", null);
        for (Map<String, Object> s : signers)
            if (s.get("user_id") != null && !s.get("user_id").equals(r.get("created_by")))
                notifier.sendAlert("USER", s.get("user_id").toString(), "Fully signed: " + d.get("title"), "Everyone has signed. Your copy and the certificate are in your documents.", "/documents/" + d.get("id"), "NORMAL", "DOCUMENTS", null);
    }

    private static Object toTs(Object o) {
        if (o instanceof java.sql.Timestamp) return o;
        return java.sql.Timestamp.from(java.time.Instant.parse(o.toString()));
    }

    /**
     * Records that a signer declined, with a reason.
     */
    @Transactional
    public Map<String, Object> decline(UUID id, String reason) {
        UserContext ctx = UserContextHolder.require();
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Tell the sender why you're declining.");
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", id);
        if (!"PENDING".equals(r.get("status"))) throw ApiException.conflict("This request is no longer open.");
        Map<String, Object> me = db.find("select * from document.signature_signer where request_id = ? and user_id = ?", id, ctx.userId()).orElseThrow(() -> ApiException.forbidden("You aren't a signer on this document."));
        if (!"PENDING".equals(me.get("status"))) throw ApiException.conflict("You've already responded.");
        db.update("update document.signature_signer set status = 'DECLINED', decline_reason = ? where id = ?", DocumentService.trim(reason, 500), me.get("id"));
        db.update("update document.signature_request set status = 'DECLINED', completed_at = now() where id = ?", id);
        db.update("update document.document set signature_status = 'DECLINED', updated_at = now() where id = ?", r.get("document_id"));
        audit.record("Document", (UUID) r.get("document_id"), "SIGNATURE_DECLINED", "PENDING", "DECLINED", Map.of("signer", String.valueOf(me.get("name")), "reason", reason));
        if (r.get("created_by") != null)
            notifier.sendAlert("USER", r.get("created_by").toString(), me.get("name") + " declined to sign", "“" + r.get("title") + "”: " + reason, "/documents/" + r.get("document_id"), "HIGH", "DOCUMENTS", null);
        return get(id);
    }

    /**
     * Cancels an open signature request.
     */
    @Transactional
    public Map<String, Object> cancel(UUID id, String reason) {
        UserContext ctx = UserContextHolder.require();
        if (!DocumentService.staff(ctx) || !ctx.has("ESIGN:CREATE")) throw ApiException.forbidden("You can't cancel signature requests.");
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", id);
        if (!"PENDING".equals(r.get("status"))) throw ApiException.conflict("This request is no longer open.");
        db.update("update document.signature_request set status = 'CANCELLED', completed_at = now() where id = ?", id);
        db.update("update document.document set signature_status = 'NONE', updated_at = now() where id = ?", r.get("document_id"));
        audit.record("Document", (UUID) r.get("document_id"), "SIGNATURE_CANCELLED", "PENDING", "CANCELLED", Map.of("reason", String.valueOf(reason)));
        for (Map<String, Object> s : db.list("select user_id from document.signature_signer where request_id = ? and status = 'PENDING'", id))
            if (s.get("user_id") != null)
                notifier.sendAlert("USER", s.get("user_id").toString(), "Signature request cancelled", "“" + r.get("title") + "” no longer needs your signature.", "/documents", "NORMAL", "DOCUMENTS", null);
        return get(id);
    }

    /**
     * Re-notifies the signers of an open request (staff with e-signature permission only).
     */
    @Transactional
    public Map<String, Object> remind(UUID id) {
        UserContext ctx = UserContextHolder.require();
        if (!DocumentService.staff(ctx) || !ctx.has("ESIGN:CREATE")) throw ApiException.forbidden("You can't send reminders.");
        Map<String, Object> r = db.one("Signature request", "select * from document.signature_request where id = ?", id);
        if (!"PENDING".equals(r.get("status"))) throw ApiException.conflict("This request is no longer open.");
        notifyPending(id, true);
        db.update("update document.signature_request set last_reminded_at = now() where id = ?", id);
        audit.record("Document", (UUID) r.get("document_id"), "SIGNATURE_REMINDED", null, null, Map.of());
        return get(id);
    }
}
