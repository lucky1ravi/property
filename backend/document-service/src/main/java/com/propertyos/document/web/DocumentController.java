package com.propertyos.document.web;

import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.document.service.*;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.*;

/**
 * Staff and portal API for the document library, versions, review, legal hold, export, requirements and e-signature.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {
    private final DocumentService docs;
    private final DocumentClassifier classifier;
    private final DocumentRequirements requirements;
    private final SignatureService signatures;
    private final DocumentAlerts alerts;
    private final ConfigClient config;

    public DocumentController(DocumentService docs, DocumentClassifier classifier, DocumentRequirements requirements, SignatureService signatures, DocumentAlerts alerts, ConfigClient config) {
        this.docs = docs;
        this.classifier = classifier;
        this.requirements = requirements;
        this.signatures = signatures;
        this.alerts = alerts;
        this.config = config;
    }

    private void feature() {
        if (!config.featureEnabled("DOCUMENTS")) throw ApiException.forbidden("Document management isn't included in your plan.");
    }

    // ---- Library
    /**
     * Returns the categories, document types, file rules and entity types the upload form needs.
     */
    @GetMapping("/meta") @RequiresPermission("DOCUMENT:VIEW")
    public Object meta() { return docs.meta(); }

    /**
     * Returns document counts for the dashboard.
     */
    @GetMapping("/summary") @RequiresPermission("DOCUMENT:VIEW")
    public Object summary() { return docs.summary(); }

    /**
     * Lists documents the user may see, filtered by the query parameters.
     */
    @GetMapping @RequiresPermission("DOCUMENT:VIEW")
    public Object list(@RequestParam Map<String, String> filters) { return docs.list(filters); }

    /**
     * Suggests a category and type for a file name before upload.
     */
    @PostMapping("/classify") @RequiresPermission("DOCUMENT:CREATE")
    public Object classify(@RequestBody Map<String, Object> b) { return classifier.classify(Req.str(b, "fileName"), Req.str(b, "entityType")); }

    /**
     * Uploads a document with its details. Rejects duplicates unless {@code allowDuplicate} is true.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @RequiresPermission("DOCUMENT:CREATE")
    public Object upload(@RequestParam("file") MultipartFile file, @RequestParam Map<String, String> p) throws java.io.IOException {
        feature();
        return docs.upload(file.getOriginalFilename(), file.getBytes(), meta(p));
    }

    static DocumentService.Meta meta(Map<String, String> p) {
        List<String> tags = new ArrayList<>();
        if (p.get("tags") != null) for (String t : p.get("tags").split(",")) if (!t.isBlank()) tags.add(t.trim());
        return new DocumentService.Meta(p.get("title"), p.get("category"), p.get("docType"), p.get("description"), p.get("entityType"),
                uuid(p.get("entityId")), uuid(p.get("propertyId")), bool(p.get("shareOwner")), bool(p.get("shareTenant")), bool(p.get("shareVendor")),
                p.get("expiresOn") == null || p.get("expiresOn").isBlank() ? null : LocalDate.parse(p.get("expiresOn")), tags, "true".equals(p.get("allowDuplicate")));
    }

    private static UUID uuid(String s) {
        try {
            return s == null || s.isBlank() ? null : UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("Not a valid id: " + s);
        }
    }

    private static Boolean bool(String s) {
        return s == null || s.isBlank() ? null : Boolean.parseBoolean(s);
    }

    /**
     * Returns one document with its versions and history.
     */
    @GetMapping("/{id}") @RequiresPermission("DOCUMENT:VIEW")
    public Object get(@PathVariable UUID id) { return docs.get(id); }

    /**
     * Changes a document's details and sharing.
     */
    @PutMapping("/{id}") @RequiresPermission("DOCUMENT:VIEW")
    public Object update(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return docs.update(id, b); }

    /**
     * Uploads a new version of a document.
     */
    @PostMapping(value = "/{id}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @RequiresPermission("DOCUMENT:CREATE")
    public Object version(@PathVariable UUID id, @RequestParam("file") MultipartFile file, @RequestParam(required = false) String note) throws java.io.IOException {
        return docs.newVersion(id, file.getOriginalFilename(), file.getBytes(), note);
    }

    /**
     * Downloads a document, or shows it inline when the type is safe to preview. Served with no-sniff and restrictive security headers.
     */
    @GetMapping("/{id}/file") @RequiresPermission("DOCUMENT:VIEW")
    public ResponseEntity<byte[]> file(@PathVariable UUID id, @RequestParam(required = false) Integer version, @RequestParam(defaultValue = "false") boolean inline) {
        DocumentService.FileData f = docs.download(id, version, inline);
        boolean show = inline && FileRules.previewable(f.mimeType());
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType(f.mimeType()));
        h.setContentDisposition((show ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(f.fileName(), StandardCharsets.UTF_8).build());
        h.setCacheControl("private, no-store");
        h.set("X-Content-Type-Options", "nosniff");
        h.set("Content-Security-Policy", "default-src 'none'; img-src 'self' data:; style-src 'unsafe-inline'; sandbox");
        return ResponseEntity.ok().headers(h).body(f.bytes());
    }

    /**
     * Accepts or rejects a file; a rejection needs a reason.
     */
    @PostMapping("/{id}/review") @RequiresPermission("DOCUMENT:EDIT")
    public Object review(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return docs.review(id, Req.required(b, "decision"), Req.str(b, "note")); }

    /**
     * Archives, restores or deletes a document (deletes are soft).
     */
    @PostMapping("/{id}/actions/{action}") @RequiresPermission("DOCUMENT:VIEW")
    public Object action(@PathVariable UUID id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> b) {
        return docs.setStatus(id, action.toUpperCase(), b == null ? null : Req.str(b, "reason"));
    }

    /**
     * Permanently removes a document and its stored files.
     */
    @PostMapping("/{id}/purge") @RequiresPermission("DOCUMENT:CONFIGURE")
    public Object purge(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) { return docs.purge(id, b == null ? null : Req.str(b, "reason")); }

    /**
     * Puts a document on or off legal hold, which blocks deletion.
     */
    @PostMapping("/{id}/legal-hold") @RequiresPermission("DOCUMENT:CONFIGURE")
    public Object hold(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return docs.legalHold(id, Req.bool(b, "on"), Req.str(b, "reason")); }

    /**
     * Downloads the chosen documents as one zip file.
     */
    @PostMapping("/export") @RequiresPermission("DOCUMENT:EXPORT")
    public ResponseEntity<byte[]> export(@RequestBody Map<String, Object> b) {
        List<UUID> ids = new ArrayList<>();
        if (b.get("ids") instanceof List<?> l) for (Object o : l) ids.add(UUID.fromString(String.valueOf(o)));
        byte[] zip = docs.zip(ids);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.parseMediaType("application/zip"));
        h.setContentDisposition(ContentDisposition.attachment().filename("documents-" + LocalDate.now() + ".zip").build());
        h.setCacheControl("private, no-store");
        return ResponseEntity.ok().headers(h).body(zip);
    }

    // ---- Requirements and deadlines
    /**
     * Returns the checklist of required documents for a record and which are present.
     */
    @GetMapping("/requirements") @RequiresPermission("DOCUMENT:VIEW")
    public Object requirements(@RequestParam String entityType, @RequestParam UUID entityId) { return requirements.requirements(entityType, entityId); }

    /**
     * Lists records that are missing required documents.
     */
    @GetMapping("/requirements/gaps") @RequiresPermission("DOCUMENT:VIEW")
    public Object gaps() { return requirements.requirementGaps(); }

    /**
     * Lists upcoming document deadlines such as expiries.
     */
    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return com.propertyos.common.events.Deadlines.publicView(alerts.deadlines()); }

    /**
     * Sends due document alerts now instead of waiting for the daily timer.
     */
    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object runAlerts() { return alerts.run(); }

    // ---- E-signatures
    /**
     * Lists people who can be asked to sign a document.
     */
    @GetMapping("/{id}/signers") @RequiresPermission("ESIGN:CREATE")
    public Object signers(@PathVariable UUID id) { return signatures.candidates(id); }

    /**
     * Sends a PDF for signature to the chosen signers.
     */
    @PostMapping("/{id}/signature-requests") @RequiresPermission("ESIGN:CREATE")
    public Object requestSignature(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return signatures.create(id, b); }

    /**
     * Lists signature requests, optionally only those waiting for the signed-in user.
     */
    @GetMapping("/signatures") @RequiresPermission("ESIGN:VIEW")
    public Object signatureList(@RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) String status) { return signatures.list(mine, status); }

    /**
     * Returns one signature request with each signer's status.
     */
    @GetMapping("/signatures/{id}") @RequiresPermission("ESIGN:VIEW")
    public Object signature(@PathVariable UUID id) { return signatures.get(id); }

    /**
     * Signs with a typed name and consent. The caller's IP address and browser are stored as evidence.
     */
    @PostMapping("/signatures/{id}/sign") @RequiresPermission("ESIGN:SIGN")
    public Object sign(@PathVariable UUID id, @RequestBody Map<String, Object> b, HttpServletRequest req) {
        String ip = Optional.ofNullable(req.getHeader("X-Forwarded-For")).map(x -> x.split(",")[0].trim()).orElse(req.getRemoteAddr());
        return signatures.sign(id, Req.str(b, "signature"), Req.bool(b, "consent"), ip, req.getHeader("User-Agent"));
    }

    /**
     * Declines to sign, with a reason.
     */
    @PostMapping("/signatures/{id}/decline") @RequiresPermission("ESIGN:SIGN")
    public Object decline(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return signatures.decline(id, Req.str(b, "reason")); }

    /**
     * Cancels a signature request that is still open.
     */
    @PostMapping("/signatures/{id}/cancel") @RequiresPermission("ESIGN:CREATE")
    public Object cancel(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) { return signatures.cancel(id, b == null ? null : Req.str(b, "reason")); }

    /**
     * Re-notifies the signers of an open request.
     */
    @PostMapping("/signatures/{id}/remind") @RequiresPermission("ESIGN:CREATE")
    public Object remind(@PathVariable UUID id) { return signatures.remind(id); }
}
