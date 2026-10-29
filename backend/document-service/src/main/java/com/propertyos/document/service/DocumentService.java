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
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * The document library: upload, versions, linking to records, sharing with owners, residents and vendors,
 * review of files sent in by others, retention and legal hold, and trash. Filing suggestions live in
 * {@link DocumentClassifier} and requirement checklists in {@link DocumentRequirements}.
 */
@Service
public class DocumentService {
    private final Db db;
    private final ConfigClient config;
    private final FileRules rules;
    private final Lookups lookups;
    private final AuditService audit;
    private final EventPublisher events;
    private final Notifier notifier;
    private final DocumentClassifier classifier;

    public DocumentService(Db db, ConfigClient config, FileRules rules, Lookups lookups, AuditService audit, EventPublisher events, Notifier notifier,
                           DocumentClassifier classifier) {
        this.db = db;
        this.config = config;
        this.rules = rules;
        this.lookups = lookups;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
        this.classifier = classifier;
    }

    /**
     * Scopes that belong to company staff rather than residents, owners or vendors.
     */
    public static final List<String> STAFF_SCOPES = List.of(UserContext.SCOPE_ORG, UserContext.SCOPE_PROPERTY, UserContext.SCOPE_PLATFORM);

    static boolean staff(UserContext ctx) {
        return ctx.system() || STAFF_SCOPES.contains(ctx.scope());
    }

    // ------------------------------------------------------------------ configuration

    /**
     * Returns the configured document categories and their types.
     */
    public JsonNode categories() {
        return config.at("documents/categories");
    }

    /**
     * Returns the display name of a category, with a readable fallback.
     */
    public String categoryLabel(String key) {
        return categories().path(key).path("label").asText(key == null ? "Other" : key.charAt(0) + key.substring(1).toLowerCase().replace('_', ' '));
    }

    boolean categorySensitive(String key) {
        return categories().path(key).path("sensitive").asBoolean(false);
    }

    int retentionYears(String key) {
        return categories().path(key).path("retentionYears").asInt(3);
    }

    /** Categories the caller may upload into. Staff may use any; others are limited by configuration. */
    public List<String> uploadCategories(UserContext ctx) {
        List<String> all = new ArrayList<>();
        categories().fieldNames().forEachRemaining(all::add);
        if (staff(ctx)) return all;
        String key = switch (ctx.scope()) {
            case UserContext.SCOPE_TENANT -> "tenantCategories";
            case UserContext.SCOPE_OWNER -> "ownerCategories";
            case UserContext.SCOPE_VENDOR -> "vendorCategories";
            default -> "none";
        };
        List<String> out = new ArrayList<>();
        config.at("documents/uploads/" + key).forEach(n -> { if (all.contains(n.asText())) out.add(n.asText()); });
        return out;
    }

    /**
     * Returns what the upload form needs: categories, types, file rules and the records documents can attach to.
     */
    public Map<String, Object> meta() {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> cats = new ArrayList<>();
        categories().fields().forEachRemaining(e -> {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("key", e.getKey());
            c.put("label", e.getValue().path("label").asText(e.getKey()));
            c.put("sensitive", e.getValue().path("sensitive").asBoolean(false));
            c.put("retentionYears", e.getValue().path("retentionYears").asInt(3));
            List<String> types = new ArrayList<>();
            e.getValue().path("types").forEach(t -> types.add(t.asText()));
            c.put("types", types);
            cats.add(c);
        });
        m.put("categories", cats);
        m.put("uploadCategories", uploadCategories(ctx));
        m.put("maxFileMb", rules.maxBytes() / 1024 / 1024);
        m.put("allowedExtensions", rules.allowed());
        m.put("requirements", config.at("documents/requirements"));
        m.put("esignEnabled", config.featureEnabled("ESIGN"));
        m.put("consentText", config.text("documents/esign/consentText", "I agree to sign electronically."));
        m.put("smartFiling", config.flag("ai/SMART_FILING/enabled", true));
        m.put("staff", staff(ctx));
        m.put("sensitiveAccess", ctx.fieldAccess("Document.sensitive"));
        return m;
    }

    // ------------------------------------------------------------------ reading

    /**
     * Document columns returned by list and detail queries (leaves out the file bytes).
     */
    private static final String COLS = """
            id, org_id, title, category, doc_type, description, status, source, entity_type, entity_id, entity_label, property_id, property_name, unit_id,
            owner_id, tenant_id, vendor_id, share_owner, share_tenant, share_vendor, sensitive, tags, expires_on, retain_until, legal_hold, review_status,
            review_note, signature_status, current_version, file_name, mime_type, size_bytes, sha256, uploaded_by, uploaded_by_name, uploader_scope,
            deleted_at, deleted_by_name, created_at, updated_at""";

    /** VISIBLE, RESTRICTED (listed, can't be opened) or HIDDEN, from the caller's role rule for sensitive documents. */
    String access(Map<String, Object> d, UserContext ctx) {
        if (!Boolean.TRUE.equals(d.get("sensitive"))) return "VISIBLE";
        if (ctx.userId() != null && ctx.userId().equals(d.get("uploaded_by"))) return "VISIBLE";
        if (!staff(ctx)) return "VISIBLE";   // shared with this party on purpose (their own W-9, their own ID)
        String rule = ctx.fieldAccess("Document.sensitive");
        return "HIDDEN".equals(rule) ? "HIDDEN" : "MASKED".equals(rule) ? "RESTRICTED" : "VISIBLE";
    }

    Map<String, Object> view(Map<String, Object> d, UserContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>(d);
        m.remove("org_id");
        m.put("category_label", categoryLabel((String) d.get("category")));
        String a = access(d, ctx);
        m.put("restricted", "RESTRICTED".equals(a));
        if ("RESTRICTED".equals(a)) {
            m.remove("sha256");
            m.put("file_name", "Restricted");
        }
        m.put("previewable", FileRules.previewable((String) d.get("mime_type")));
        Object exp = d.get("expires_on");
        m.put("days_to_expiry", exp == null ? null : ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(exp.toString())));
        m.put("mine", ctx.userId() != null && ctx.userId().equals(d.get("uploaded_by")));
        return m;
    }

    /**
     * Lists documents the user may see. Residents, owners and vendors see only documents shared with them.
     *
     * @param f filters such as category, type, status, record and search text
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> f) {
        UserContext ctx = UserContextHolder.require();
        StringBuilder sql = new StringBuilder("select " + COLS + " from document.document where 1=1");
        List<Object> args = new ArrayList<>();
        String status = f.getOrDefault("status", "ACTIVE");
        if ("ALL".equals(status)) sql.append(" and status <> 'DELETED'");
        else { sql.append(" and status = ?"); args.add(status); }
        if ("DELETED".equals(status) && !ctx.has("DOCUMENT:DELETE")) throw ApiException.forbidden("You can't view the trash.");
        if (has(f, "category")) { sql.append(" and category = ?"); args.add(f.get("category")); }
        if (has(f, "docType")) { sql.append(" and doc_type = ?"); args.add(f.get("docType")); }
        if (has(f, "entityType")) { sql.append(" and entity_type = ?"); args.add(f.get("entityType")); }
        if (has(f, "entityId")) { sql.append(" and entity_id = ?"); args.add(UUID.fromString(f.get("entityId"))); }
        if (has(f, "propertyId")) { sql.append(" and property_id = ?"); args.add(UUID.fromString(f.get("propertyId"))); }
        if (has(f, "ownerId")) { sql.append(" and owner_id = ?"); args.add(UUID.fromString(f.get("ownerId"))); }
        if (has(f, "tenantId")) { sql.append(" and tenant_id = ?"); args.add(UUID.fromString(f.get("tenantId"))); }
        if (has(f, "vendorId")) { sql.append(" and vendor_id = ?"); args.add(UUID.fromString(f.get("vendorId"))); }
        if (has(f, "review")) { sql.append(" and review_status = ?"); args.add(f.get("review")); }
        if (has(f, "signature")) {
            if ("ANY".equals(f.get("signature"))) sql.append(" and signature_status <> 'NONE'");
            else { sql.append(" and signature_status = ?"); args.add(f.get("signature")); }
        }
        if ("true".equals(f.get("expiring"))) sql.append(" and expires_on is not null and expires_on <= current_date + 45");
        if ("true".equals(f.get("mine"))) { sql.append(" and uploaded_by = ?"); args.add(ctx.userId()); }
        if (has(f, "q")) {
            sql.append(" and (title ilike ? or coalesce(doc_type,'') ilike ? or coalesce(entity_label,'') ilike ? or coalesce(property_name,'') ilike ? or coalesce(file_name,'') ilike ? or array_to_string(tags, ' ') ilike ?)");
            String q = "%" + f.get("q").trim() + "%";
            for (int i = 0; i < 6; i++) args.add(q);
        }
        sql.append(" order by updated_at desc limit ").append(Math.min(Integer.parseInt(f.getOrDefault("limit", "500")), 1000));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> d : db.list(sql.toString(), args.toArray())) {
            if ("HIDDEN".equals(access(d, ctx))) continue;
            rows.add(view(d, ctx));
        }
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) byCategory.merge((String) r.get("category"), 1L, Long::sum);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows);
        out.put("byCategory", byCategory);
        return out;
    }

    private static boolean has(Map<String, String> f, String k) {
        return f.get(k) != null && !f.get(k).isBlank();
    }

    Map<String, Object> load(UUID id) {
        Map<String, Object> d = db.one("Document", "select " + COLS + " from document.document where id = ?", id);
        if ("HIDDEN".equals(access(d, UserContextHolder.require()))) throw ApiException.notFound("Document");
        return d;
    }

    /**
     * Returns one document with versions and history, if the user may see it.
     *
     * @throws ApiException (404) when missing or not visible
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> d = load(id);
        Map<String, Object> m = view(d, ctx);
        m.put("versions", db.list("select id, version, file_name, mime_type, size_bytes, sha256, note, created_by_name, created_at from document.document_version where document_id = ? order by version desc", id));
        List<Map<String, Object>> reqs = db.list("select id, title, message, status, due_on, created_by_name, created_at, completed_at, certificate_id, document_sha256 from document.signature_request where document_id = ? order by created_at desc", id);
        for (Map<String, Object> r : reqs)
            r.put("signers", elevated(() -> db.list("select id, signer_order, name, role_label, party_type, status, viewed_at, signed_at, decline_reason, user_id from document.signature_signer where request_id = ? order by signer_order, name", r.get("id"))));
        m.put("signatureRequests", reqs);
        List<Map<String, Object>> history = new ArrayList<>();
        for (Map<String, Object> h : audit.timeline(id)) {
            Map<String, Object> x = new LinkedHashMap<>(h);
            x.remove("details");
            history.add(x);
        }
        m.put("history", history);
        m.put("can", Map.of(
                "edit", canEdit(d, ctx), "delete", ctx.has("DOCUMENT:DELETE") && staff(ctx), "configure", ctx.has("DOCUMENT:CONFIGURE"),
                "review", staff(ctx) && ctx.has("DOCUMENT:EDIT"), "requestSignature", staff(ctx) && ctx.has("ESIGN:CREATE") && config.featureEnabled("ESIGN"),
                "newVersion", canEdit(d, ctx) || Boolean.TRUE.equals(m.get("mine"))));
        return m;
    }

    boolean canEdit(Map<String, Object> d, UserContext ctx) {
        if (!"GENERATED".equals(d.get("source")) && ctx.userId() != null && ctx.userId().equals(d.get("uploaded_by"))) return true;
        return staff(ctx) && ctx.has("DOCUMENT:EDIT");
    }

    // ------------------------------------------------------------------ writing

    /** Fields accepted with an upload or an edit. */
    public record Meta(String title, String category, String docType, String description, String entityType, UUID entityId, UUID propertyId,
                       Boolean shareOwner, Boolean shareTenant, Boolean shareVendor, LocalDate expiresOn, List<String> tags, boolean allowDuplicate) {
    }

    /**
     * Validates and stores a new document, rejecting duplicates and unsafe files.
     *
     * @param originalName the uploaded file name
     * @param data         the file bytes
     * @param meta         the document's details
     * @throws ApiException (400/409) for an invalid file or a duplicate
     */
    @Transactional
    public Map<String, Object> upload(String originalName, byte[] data, Meta meta) {
        UserContext ctx = UserContextHolder.require();
        FileRules.Checked file = rules.check(originalName, data);
        Map<String, Object> suggestion = classifier.classify(file.fileName(), meta.entityType());
        String category = blank(meta.category()) ? (String) suggestion.get("category") : meta.category();
        if (!categories().has(category)) throw ApiException.badRequest("Unknown document category: " + category);
        if (!uploadCategories(ctx).contains(category))
            throw ApiException.forbidden("You can't upload " + categoryLabel(category).toLowerCase() + ". Allowed: " +
                    String.join(", ", uploadCategories(ctx).stream().map(this::categoryLabel).toList()) + ".");
        String docType = blank(meta.docType()) ? (category.equals(suggestion.get("category")) ? (String) suggestion.get("docType") : null) : meta.docType();

        // Where the file belongs. Links always come from the owning service, never from the browser.
        String entityType = blank(meta.entityType()) ? null : meta.entityType();
        UUID entityId = meta.entityId();
        if (entityType != null && entityId == null) throw ApiException.badRequest("Say which " + entityType.toLowerCase().replace('_', ' ') + " the file belongs to.");
        Lookups.Links links;
        switch (ctx.scope()) {
            case UserContext.SCOPE_TENANT -> {
                if (entityType == null) {
                    var lease = lookups.tenantLease(ctx.partyId());
                    if (lease.isPresent()) { entityType = "LEASE"; entityId = UUID.fromString(lease.get().path("id").asText()); }
                    else { entityType = "TENANT"; entityId = ctx.partyId(); }
                }
                links = entityType.equals("TENANT") && entityId.equals(ctx.partyId()) ? new Lookups.Links(null, null, null, null, ctx.partyId(), null, ctx.name()) : lookups.resolve(entityType, entityId);
                if (!ctx.partyId().equals(links.tenantId())) throw ApiException.forbidden("You can only add files to your own lease and requests.");
            }
            case UserContext.SCOPE_OWNER -> {
                if (entityType == null) { entityType = meta.propertyId() != null ? "PROPERTY" : "OWNER"; entityId = meta.propertyId() != null ? meta.propertyId() : ctx.partyId(); }
                links = lookups.resolve(entityType, entityId);
                if (!ctx.partyId().equals(links.ownerId())) throw ApiException.forbidden("You can only add files to your own properties.");
            }
            case UserContext.SCOPE_VENDOR -> {
                if (entityType == null) { entityType = "VENDOR"; entityId = ctx.partyId(); }
                links = lookups.resolve(entityType, entityId);
                if (!ctx.partyId().equals(links.vendorId())) throw ApiException.forbidden("You can only add files to your own company and jobs.");
            }
            default -> {
                if (entityType == null && meta.propertyId() != null) { entityType = "PROPERTY"; entityId = meta.propertyId(); }
                links = lookups.resolve(entityType, entityId);
            }
        }
        if (UserContext.SCOPE_PROPERTY.equals(ctx.scope()) && links.propertyId() != null && (ctx.propertyIds() == null || !ctx.propertyIds().contains(links.propertyId())))
            throw ApiException.forbidden("That property isn't assigned to you.");

        if (config.flag("documents/storage/blockDuplicates", true) && !meta.allowDuplicate()) {
            Optional<Map<String, Object>> dup = db.find("select id, title from document.document where sha256 = ? and status <> 'DELETED' and coalesce(entity_id::text,'') = coalesce(?::text,'') limit 1", file.sha256(), entityId);
            if (dup.isPresent()) throw ApiException.conflict("This exact file is already filed here as “" + dup.get().get("title") + "”.");
        }

        boolean[] share = defaultSharing(category, docType, entityType, links, ctx);
        boolean shareOwner = meta.shareOwner() != null && staff(ctx) ? meta.shareOwner() : share[0];
        boolean shareTenant = meta.shareTenant() != null && staff(ctx) ? meta.shareTenant() : share[1];
        boolean shareVendor = meta.shareVendor() != null && staff(ctx) ? meta.shareVendor() : share[2];
        boolean external = !staff(ctx);
        String review = external && config.flag("documents/uploads/reviewUploadsFromOthers", true) ? "PENDING" : "NONE";
        String title = blank(meta.title()) ? defaultTitle(docType, file.fileName(), links) : meta.title().trim();

        UUID id = UUID.randomUUID();
        insert(id, ctx.orgId(), title, category, docType, meta.description(), "UPLOAD", entityType, entityId, links, shareOwner, shareTenant, shareVendor,
                categorySensitive(category), meta.tags(), meta.expiresOn(), review, ctx.userId(), ctx.name(), ctx.scope(), file, data, "Uploaded");
        audit.record("Document", id, "DOCUMENT_UPLOADED", null, "ACTIVE", Map.of("title", title, "category", category, "file", file.fileName(), "sha256", file.sha256()));
        events.publish("DocumentUploaded", "Document", id, Map.of("category", category, "docType", String.valueOf(docType), "entityType", String.valueOf(entityType),
                "entityId", String.valueOf(entityId), "external", external));
        afterUpload(id, title, category, docType, entityType, links, meta.expiresOn(), external, ctx);
        return get(id);
    }

    /** Applicant upload (no account): runs as the system for the application's organisation. */
    @Transactional
    public Map<String, Object> uploadForApplicant(UUID applicationId, String applicantName, String originalName, byte[] data, String category, String docType) {
        UserContext ctx = UserContextHolder.require();
        List<String> allowed = new ArrayList<>();
        config.at("documents/uploads/applicantCategories").forEach(n -> allowed.add(n.asText()));
        if (allowed.isEmpty()) allowed.addAll(List.of("APPLICATION", "IDENTITY"));
        FileRules.Checked file = rules.check(originalName, data);
        if (blank(category)) category = (String) classifier.classify(file.fileName(), "APPLICATION").get("category");
        if (!allowed.contains(category)) category = "APPLICATION";
        Lookups.Links links = lookups.resolve("APPLICATION", applicationId);
        Optional<Map<String, Object>> dup = db.find("select id from document.document where sha256 = ? and entity_id = ? and status <> 'DELETED'", file.sha256(), applicationId);
        if (dup.isPresent()) throw ApiException.conflict("You've already uploaded this file.");
        long count = db.value(Long.class, "select count(*) from document.document where entity_id = ? and uploader_scope = 'APPLICANT'", applicationId);
        if (count >= 20) throw ApiException.badRequest("You've reached the limit of 20 files for one application. Contact the leasing office.");
        UUID id = UUID.randomUUID();
        String type = blank(docType) ? (String) classifier.classify(file.fileName(), "APPLICATION").get("docType") : docType;
        insert(id, ctx.orgId(), type + " – " + applicantName, category, type, null, "UPLOAD", "APPLICATION", applicationId, links, false, false, false,
                true, List.of(), null, "PENDING", null, applicantName, "APPLICANT", file, data, "Uploaded by the applicant");
        audit.record("Document", id, "DOCUMENT_UPLOADED", null, "ACTIVE", Map.of("title", type, "by", applicantName + " (applicant)", "file", file.fileName(), "sha256", file.sha256()));
        if (links.propertyId() != null)
            notifier.send("PROPERTY", links.propertyId().toString(), "New applicant document", applicantName + " uploaded " + type.toLowerCase() + " for " + links.label() + ".", "/leasing/applications/" + applicationId, "NORMAL");
        return Map.of("id", id, "title", type, "fileName", file.fileName(), "status", "RECEIVED");
    }

    /** Stores a file the platform produced itself (signed lease, signature certificate). */
    public UUID insertGenerated(UUID orgId, String title, String category, String docType, String entityType, UUID entityId, Lookups.Links links,
                                boolean shareOwner, boolean shareTenant, boolean shareVendor, String fileName, byte[] pdf, String signatureStatus, String note) {
        FileRules.Checked file = new FileRules.Checked(fileName, "pdf", "application/pdf", pdf.length, FileRules.sha256(pdf));
        UUID id = UUID.randomUUID();
        insert(id, orgId, title, category, docType, null, "GENERATED", entityType, entityId, links, shareOwner, shareTenant, shareVendor,
                categorySensitive(category), List.of(), null, "NONE", null, "PropertyOS", "SYSTEM", file, pdf, note);
        if (signatureStatus != null) db.update("update document.document set signature_status = ? where id = ?", signatureStatus, id);
        return id;
    }

    /**
     * Writes the document row, its first version and its history entry.
     */
    public void insert(UUID id, UUID orgId, String title, String category, String docType, String description, String source, String entityType, UUID entityId,
                Lookups.Links links, boolean shareOwner, boolean shareTenant, boolean shareVendor, boolean sensitive, List<String> tags, LocalDate expiresOn,
                String review, UUID by, String byName, String byScope, FileRules.Checked file, byte[] data, String note) {
        LocalDate retain = LocalDate.now().plusYears(retentionYears(category));
        db.update("""
                insert into document.document (id, org_id, title, category, doc_type, description, source, entity_type, entity_id, entity_label, property_id, property_name,
                    unit_id, owner_id, tenant_id, vendor_id, share_owner, share_tenant, share_vendor, sensitive, tags, expires_on, retain_until, review_status,
                    file_name, mime_type, size_bytes, sha256, uploaded_by, uploaded_by_name, uploader_scope)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::text[],?,?,?,?,?,?,?,?,?,?)""",
                id, orgId, trim(title, 200), category, docType, description, source, entityType, entityId, trim(links.label(), 200), links.propertyId(), trim(links.propertyName(), 160),
                links.unitId(), links.ownerId(), links.tenantId(), links.vendorId(), shareOwner, shareTenant, shareVendor, sensitive,
                pgArray(tags), expiresOn, retain, review, file.fileName(), file.mimeType(), file.size(), file.sha256(), by, byName, byScope);
        addVersion(id, orgId, 1, file, data, note, by, byName);
    }

    void addVersion(UUID docId, UUID orgId, int version, FileRules.Checked file, byte[] data, String note, UUID by, String byName) {
        UUID blob = UUID.randomUUID();
        db.update("insert into document.blob (id, org_id, content) values (?,?,?)", blob, orgId, data);
        db.update("insert into document.document_version (id, document_id, org_id, version, file_name, mime_type, size_bytes, sha256, blob_id, note, created_by, created_by_name) values (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), docId, orgId, version, file.fileName(), file.mimeType(), file.size(), file.sha256(), blob, note, by, byName);
    }

    private void afterUpload(UUID id, String title, String category, String docType, String entityType, Lookups.Links links, LocalDate expires, boolean external, UserContext ctx) {
        if ("Certificate of insurance".equals(docType) && links.vendorId() != null && expires != null) lookups.syncVendorInsurance(links.vendorId(), expires);
        if (!external) return;
        String who = ctx.name() + switch (ctx.scope()) { case "TENANT" -> " (resident)"; case "OWNER" -> " (owner)"; case "VENDOR" -> " (vendor)"; default -> ""; };
        String body = who + " added “" + title + "”" + (links.label() == null ? "" : " to " + links.label()) + ". Review it in Documents.";
        if (links.propertyId() != null) notifier.send("PROPERTY", links.propertyId().toString(), "New document to review", body, "/documents/" + id, "NORMAL");
        notifier.send("ROLE", "PROPERTY_MANAGER", "New document to review", body, "/documents/" + id, "NORMAL");
    }

    /** {owner, tenant, vendor}: who a new file is shared with unless staff choose otherwise. */
    static boolean[] defaultSharing(String category, String docType, String entityType, Lookups.Links l, UserContext ctx) {
        boolean o = l.ownerId() != null, t = l.tenantId() != null, v = l.vendorId() != null;
        switch (ctx.scope()) {
            case UserContext.SCOPE_TENANT: return new boolean[]{false, true, false};
            case UserContext.SCOPE_OWNER: return new boolean[]{true, false, false};
            case UserContext.SCOPE_VENDOR: return new boolean[]{o && "WORK_ORDER".equals(entityType) && !"TAX".equals(category), false, true};
            default: break;
        }
        return switch (category) {
            case "LEASE", "NOTICE" -> new boolean[]{o, t, false};
            case "INSURANCE" -> new boolean[]{o && !"Renters insurance".equals(docType), t && "Renters insurance".equals(docType), v};
            case "BILL", "STATEMENT", "PROPERTY" -> new boolean[]{o, false, v && "BILL".equals(category)};
            case "WORK_ORDER" -> new boolean[]{o, t, v};
            case "TAX", "CONTRACT" -> new boolean[]{o && ("OWNER".equals(entityType) || "PROPERTY".equals(entityType)), false, v && "VENDOR".equals(entityType)};
            default -> new boolean[]{false, false, false};
        };
    }

    private static String defaultTitle(String docType, String fileName, Lookups.Links l) {
        String base = docType != null && !docType.isBlank() && !"Other".equals(docType) ? docType : fileName.replaceAll("\\.[A-Za-z0-9]+$", "");
        return l.label() == null || l.label().isBlank() ? base : base + " – " + l.label();
    }

    /**
     * Stores a new version of a document and makes it current.
     *
     * @param note what changed
     */
    @Transactional
    public Map<String, Object> newVersion(UUID id, String originalName, byte[] data, String note) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> d = load(id);
        boolean mine = ctx.userId() != null && ctx.userId().equals(d.get("uploaded_by"));
        if (!canEdit(d, ctx) && !mine) throw ApiException.forbidden("You can't replace this file.");
        if (!"ACTIVE".equals(d.get("status"))) throw ApiException.conflict("Restore the document before adding a new version.");
        if (List.of("PENDING", "PARTIAL").contains(d.get("signature_status"))) throw ApiException.conflict("This document is out for signature. Cancel the request before changing the file.");
        FileRules.Checked file = rules.check(originalName, data);
        if (file.sha256().equals(d.get("sha256"))) throw ApiException.conflict("That's the same file as the current version.");
        int v = ((Number) d.get("current_version")).intValue() + 1;
        addVersion(id, (UUID) d.get("org_id"), v, file, data, blank(note) ? "New version" : note, ctx.userId(), ctx.name());
        String review = !staff(ctx) && config.flag("documents/uploads/reviewUploadsFromOthers", true) ? "PENDING" : (String) d.get("review_status");
        String sig = "SIGNED".equals(d.get("signature_status")) ? "NONE" : (String) d.get("signature_status");
        db.update("update document.document set current_version = ?, file_name = ?, mime_type = ?, size_bytes = ?, sha256 = ?, review_status = ?, signature_status = ?, updated_at = now() where id = ?",
                v, file.fileName(), file.mimeType(), file.size(), file.sha256(), review, sig, id);
        audit.record("Document", id, "VERSION_ADDED", "v" + (v - 1), "v" + v, Map.of("file", file.fileName(), "note", String.valueOf(note), "sha256", file.sha256()));
        return get(id);
    }

    /**
     * Changes a document's title, details, tags, expiry and sharing, if the user may edit it.
     */
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> b) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> d = load(id);
        if (!canEdit(d, ctx)) throw ApiException.forbidden("You can't edit this document.");
        List<String> sets = new ArrayList<>();
        List<Object> args = new ArrayList<>();
        Map<String, Object> changes = new LinkedHashMap<>();
        for (String[] f : new String[][]{{"title", "title"}, {"docType", "doc_type"}, {"description", "description"}}) {
            if (b.containsKey(f[0])) { sets.add(f[1] + " = ?"); args.add(trim(str(b.get(f[0])), f[0].equals("title") ? 200 : 1000)); changes.put(f[0], b.get(f[0])); }
        }
        if (b.containsKey("category")) {
            String c = str(b.get("category"));
            if (!categories().has(c)) throw ApiException.badRequest("Unknown document category: " + c);
            sets.add("category = ?"); args.add(c);
            sets.add("sensitive = ?"); args.add(categorySensitive(c) || Boolean.TRUE.equals(b.get("sensitive")));
            changes.put("category", c);
        }
        if (b.containsKey("expiresOn")) {
            String e = str(b.get("expiresOn"));
            LocalDate exp = blank(e) ? null : LocalDate.parse(e);
            sets.add("expires_on = ?"); args.add(exp); changes.put("expiresOn", e);
            if (exp != null && "Certificate of insurance".equals(d.get("doc_type")) && d.get("vendor_id") != null) lookups.syncVendorInsurance((UUID) d.get("vendor_id"), exp);
        }
        if (b.containsKey("tags")) {
            List<String> tags = new ArrayList<>();
            if (b.get("tags") instanceof List<?> l) l.forEach(x -> tags.add(String.valueOf(x)));
            sets.add("tags = ?::text[]"); args.add(pgArray(tags)); changes.put("tags", tags);
        }
        if (staff(ctx)) {
            for (String[] f : new String[][]{{"shareOwner", "share_owner"}, {"shareTenant", "share_tenant"}, {"shareVendor", "share_vendor"}, {"sensitive", "sensitive"}}) {
                if (b.containsKey(f[0])) { sets.add(f[1] + " = ?"); args.add(Boolean.TRUE.equals(b.get(f[0]))); changes.put(f[0], b.get(f[0])); }
            }
        }
        if (sets.isEmpty()) return get(id);
        sets.add("updated_at = now()");
        args.add(id);
        db.update("update document.document set " + String.join(", ", sets) + " where id = ?", args.toArray());
        boolean shared = Boolean.TRUE.equals(changes.get("shareOwner")) || Boolean.TRUE.equals(changes.get("shareTenant")) || Boolean.TRUE.equals(changes.get("shareVendor"));
        audit.record("Document", id, shared ? "DOCUMENT_SHARED" : "DOCUMENT_UPDATED", null, null, Map.of("changes", changes.toString()));
        if (Boolean.TRUE.equals(changes.get("shareTenant")) && !Boolean.TRUE.equals(d.get("share_tenant")) && d.get("tenant_id") != null)
            notifier.sendAlert("PARTY", d.get("tenant_id").toString(), "New document shared with you", "“" + d.get("title") + "” is now in your documents.", "/documents/" + id, "NORMAL", "DOCUMENTS", null);
        if (Boolean.TRUE.equals(changes.get("shareOwner")) && !Boolean.TRUE.equals(d.get("share_owner")) && d.get("owner_id") != null)
            notifier.sendAlert("PARTY", d.get("owner_id").toString(), "New document shared with you", "“" + d.get("title") + "” is now in your documents.", "/documents/" + id, "NORMAL", "DOCUMENTS", null);
        if (Boolean.TRUE.equals(changes.get("shareVendor")) && !Boolean.TRUE.equals(d.get("share_vendor")) && d.get("vendor_id") != null)
            notifier.sendAlert("PARTY", d.get("vendor_id").toString(), "New document shared with you", "“" + d.get("title") + "” is now in your documents.", "/documents/" + id, "NORMAL", "DOCUMENTS", null);
        return get(id);
    }

    /**
     * Records whether a file was accepted or rejected.
     *
     * @param decision {@code ACCEPTED} or {@code REJECTED}
     * @param note    why; required for a rejection
     * @throws ApiException (400) for another decision or a missing reason
     */
    @Transactional
    public Map<String, Object> review(UUID id, String decision, String note) {
        UserContext ctx = UserContextHolder.require();
        if (!staff(ctx) || !ctx.has("DOCUMENT:EDIT")) throw ApiException.forbidden("You can't review documents.");
        if (!List.of("ACCEPTED", "REJECTED").contains(decision)) throw ApiException.badRequest("Decision must be ACCEPTED or REJECTED.");
        if ("REJECTED".equals(decision) && blank(note)) throw ApiException.badRequest("Say why the file was rejected so the sender can fix it.");
        Map<String, Object> d = load(id);
        db.update("update document.document set review_status = ?, review_note = ?, updated_at = now() where id = ?", decision, trim(note, 500), id);
        audit.record("Document", id, "DOCUMENT_" + decision, (String) d.get("review_status"), decision, Map.of("note", String.valueOf(note)));
        if (d.get("uploaded_by") != null) {
            String t = "REJECTED".equals(decision) ? "Document needs another look" : "Document accepted";
            String body = "“" + d.get("title") + "” was " + decision.toLowerCase() + ("REJECTED".equals(decision) ? ": " + note + ". Upload a new version." : ".");
            notifier.sendAlert("USER", d.get("uploaded_by").toString(), t, body, "/documents/" + id, "REJECTED".equals(decision) ? "HIGH" : "NORMAL", "DOCUMENTS", null);
        }
        return get(id);
    }

    /**
     * Archives, restores or deletes a document after checking permissions. Deleting is blocked by legal hold, open signature requests and (for signed files) a missing administrator permission.
     *
     * @param action {@code ARCHIVE}, {@code RESTORE} or {@code DELETE}
     * @param reason why, recorded in the history
     */
    @Transactional
    public Map<String, Object> setStatus(UUID id, String action, String reason) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> d = load(id);
        String from = (String) d.get("status");
        switch (action) {
            case "ARCHIVE" -> {
                if (!canEdit(d, ctx)) throw ApiException.forbidden("You can't archive this document.");
                db.update("update document.document set status = 'ARCHIVED', updated_at = now() where id = ?", id);
            }
            case "RESTORE" -> {
                if (!(staff(ctx) && (ctx.has("DOCUMENT:DELETE") || ctx.has("DOCUMENT:EDIT")))) throw ApiException.forbidden("You can't restore documents.");
                db.update("update document.document set status = 'ACTIVE', deleted_at = null, deleted_by_name = null, updated_at = now() where id = ?", id);
            }
            case "DELETE" -> {
                boolean own = ctx.userId() != null && ctx.userId().equals(d.get("uploaded_by")) && "PENDING".equals(d.get("review_status"));
                if (!(staff(ctx) && ctx.has("DOCUMENT:DELETE")) && !own) throw ApiException.forbidden("You can't delete this document.");
                if (Boolean.TRUE.equals(d.get("legal_hold"))) throw ApiException.conflict("This document is on legal hold and can't be deleted.");
                if (List.of("PENDING", "PARTIAL").contains(d.get("signature_status"))) throw ApiException.conflict("Cancel the signature request before deleting this document.");
                if ("SIGNED".equals(d.get("signature_status")) && !ctx.has("DOCUMENT:CONFIGURE")) throw ApiException.conflict("Signed documents can only be deleted by an administrator.");
                db.update("update document.document set status = 'DELETED', deleted_at = now(), deleted_by_name = ?, updated_at = now() where id = ?", ctx.name(), id);
            }
            default -> throw ApiException.badRequest("Unknown action " + action);
        }
        String to = switch (action) { case "ARCHIVE" -> "ARCHIVED"; case "RESTORE" -> "ACTIVE"; default -> "DELETED"; };
        audit.record("Document", id, "ACTIVE".equals(to) ? "DOCUMENT_RESTORED" : "DOCUMENT_" + to, from, to, Map.of("reason", String.valueOf(reason)));
        return Map.of("id", id, "status", to);
    }

    /** Permanently removes a document from the trash, once its retention period has passed (or never, while on legal hold). */
    @Transactional
    public Map<String, Object> purge(UUID id, String reason) {
        UserContext ctx = UserContextHolder.require();
        if (!ctx.has("DOCUMENT:CONFIGURE")) throw ApiException.forbidden("Only administrators can permanently delete documents.");
        Map<String, Object> d = load(id);
        if (!"DELETED".equals(d.get("status"))) throw ApiException.conflict("Move the document to the trash first.");
        if (Boolean.TRUE.equals(d.get("legal_hold"))) throw ApiException.conflict("This document is on legal hold.");
        LocalDate retain = d.get("retain_until") == null ? null : LocalDate.parse(d.get("retain_until").toString());
        if (retain != null && retain.isAfter(LocalDate.now()) && blank(reason))
            throw ApiException.conflict("The retention policy keeps this document until " + retain + ". Give a reason to delete it early.");
        purgeRow(id);
        audit.record("Document", id, "DOCUMENT_PURGED", "DELETED", null, Map.of("title", String.valueOf(d.get("title")), "reason", String.valueOf(reason), "retainUntil", String.valueOf(retain)));
        return Map.of("id", id, "status", "PURGED");
    }

    /**
     * Deletes a document row and any stored files no other document uses.
     */
    public void purgeRow(UUID id) {
        List<Map<String, Object>> blobs = db.list("select blob_id from document.document_version where document_id = ?", id);
        db.update("delete from document.document where id = ?", id);
        for (Map<String, Object> bl : blobs) db.update("delete from document.blob where id = ?", bl.get("blob_id"));
    }

    /**
     * Puts a document on or off legal hold.
     *
     * @param on true to hold
     * @param reason why, recorded in the history
     */
    @Transactional
    public Map<String, Object> legalHold(UUID id, boolean on, String reason) {
        UserContext ctx = UserContextHolder.require();
        if (!ctx.has("DOCUMENT:CONFIGURE")) throw ApiException.forbidden("Only administrators can place legal holds.");
        if (on && blank(reason)) throw ApiException.badRequest("Give a reason for the legal hold, for example the case or claim it relates to.");
        load(id);
        db.update("update document.document set legal_hold = ?, updated_at = now() where id = ?", on, id);
        audit.record("Document", id, on ? "LEGAL_HOLD_PLACED" : "LEGAL_HOLD_RELEASED", null, null, Map.of("reason", String.valueOf(reason)));
        return get(id);
    }

    // ------------------------------------------------------------------ files

    /**
     * A file ready to send to a client.
     *
     * @param fileName the download name
     * @param mimeType the content type
     * @param bytes    the content
     */
    public record FileData(String fileName, String mimeType, byte[] bytes) {
    }

    /**
     * Reads a document's file after checking the user may see it.
     *
     * @param id      the document
     * @param version a specific version, or null for the current one
     * @param inline true when the file is for on-screen preview
     */
    @Transactional
    public FileData download(UUID id, Integer version, boolean inline) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> d = load(id);
        if ("RESTRICTED".equals(access(d, ctx))) throw ApiException.forbidden("Your role can see that this document exists but can't open it.");
        if ("DELETED".equals(d.get("status")) && !ctx.has("DOCUMENT:DELETE")) throw ApiException.notFound("Document");
        int v = version == null ? ((Number) d.get("current_version")).intValue() : version;
        Map<String, Object> ver = db.one("Version", "select v.file_name, v.mime_type, b.content from document.document_version v join document.blob b on b.id = v.blob_id where v.document_id = ? and v.version = ?", id, v);
        audit.record("Document", id, inline ? "DOCUMENT_VIEWED" : "DOCUMENT_DOWNLOADED", null, null, Map.of("version", v));
        return new FileData((String) ver.get("file_name"), (String) ver.get("mime_type"), (byte[]) ver.get("content"));
    }

    /**
     * Packs the chosen documents the user may see into one zip.
     *
     * @param ids the documents
     */
    @Transactional
    public byte[] zip(List<UUID> ids) {
        UserContext ctx = UserContextHolder.require();
        if (!ctx.has("DOCUMENT:EXPORT")) throw ApiException.forbidden("You can't export documents.");
        if (ids.isEmpty() || ids.size() > 100) throw ApiException.badRequest("Choose between 1 and 100 documents.");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        Set<String> names = new HashSet<>();
        try (java.util.zip.ZipOutputStream z = new java.util.zip.ZipOutputStream(out)) {
            StringBuilder index = new StringBuilder("Title,Category,Type,Linked to,File,SHA-256\n");
            for (UUID id : ids) {
                Map<String, Object> d;
                try { d = load(id); } catch (ApiException e) { continue; }
                if (!"VISIBLE".equals(access(d, ctx))) continue;
                Map<String, Object> ver = db.one("Version", "select v.file_name, b.content from document.document_version v join document.blob b on b.id = v.blob_id where v.document_id = ? and v.version = ?", id, d.get("current_version"));
                String folder = categoryLabel((String) d.get("category")).replaceAll("[^A-Za-z0-9 ]", "");
                String name = folder + "/" + ver.get("file_name");
                for (int i = 2; names.contains(name); i++) name = folder + "/" + i + "-" + ver.get("file_name");
                names.add(name);
                z.putNextEntry(new java.util.zip.ZipEntry(name));
                z.write((byte[]) ver.get("content"));
                z.closeEntry();
                index.append(csv(d.get("title"))).append(',').append(csv(categoryLabel((String) d.get("category")))).append(',').append(csv(d.get("doc_type"))).append(',')
                        .append(csv(d.get("entity_label"))).append(',').append(csv(name)).append(',').append(d.get("sha256")).append('\n');
                audit.record("Document", id, "DOCUMENT_EXPORTED", null, null, Map.of("zip", true));
            }
            z.putNextEntry(new java.util.zip.ZipEntry("index.csv"));
            z.write(index.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            z.closeEntry();
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    private static String csv(Object o) {
        String s = o == null ? "" : o.toString();
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ------------------------------------------------------------------ overview

    /**
     * Returns counts of active, archived, pending-review and soon-to-expire documents for the dashboard.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = db.list("select category, sensitive, uploaded_by, review_status, signature_status, expires_on, size_bytes, status from document.document where status <> 'DELETED'");
        long active = 0, review = 0, expiring = 0, bytes = 0, archived = 0;
        Map<String, Long> byCategory = new LinkedHashMap<>();
        for (Map<String, Object> r : rows) {
            if ("HIDDEN".equals(access(r, ctx))) continue;
            if ("ARCHIVED".equals(r.get("status"))) { archived++; continue; }
            active++;
            byCategory.merge((String) r.get("category"), 1L, Long::sum);
            if ("PENDING".equals(r.get("review_status"))) review++;
            if (r.get("expires_on") != null && !LocalDate.parse(r.get("expires_on").toString()).isAfter(LocalDate.now().plusDays(30))) expiring++;
            bytes += r.get("size_bytes") == null ? 0 : ((Number) r.get("size_bytes")).longValue();
        }
        m.put("active", active);
        m.put("archived", archived);
        m.put("pendingReview", review);
        m.put("expiringSoon", expiring);
        m.put("storageBytes", bytes);
        m.put("byCategory", byCategory);
        m.put("inTrash", ctx.has("DOCUMENT:DELETE") && staff(ctx) ? db.value(Long.class, "select count(*) from document.document where status = 'DELETED'") : 0);
        m.put("awaitingMySignature", db.value(Long.class, "select count(*) from document.signature_signer s join document.signature_request r on r.id = s.request_id where s.status = 'PENDING' and r.status = 'PENDING' and s.user_id = ?", ctx.userId()));
        m.put("outForSignature", staff(ctx) ? db.value(Long.class, "select count(*) from document.signature_request where status = 'PENDING'") : 0);
        return m;
    }

    // ------------------------------------------------------------------ helpers

    /**
     * Runs platform-owned work (for example filing a signature certificate) with row-level security lifted for the rest
     * of this step only. Used when a resident's or owner's action makes the platform create a record they could not
     * create themselves; the previous setting is always restored.
     */
    public <T> T elevated(java.util.function.Supplier<T> work) {
        String before = db.value(String.class, "select coalesce(current_setting('app.bypass_rls', true), 'off')");
        db.value(String.class, "select set_config('app.bypass_rls', 'on', true)");
        try {
            return work.get();
        } finally {
            db.value(String.class, "select set_config('app.bypass_rls', ?, true)", before == null || before.isBlank() ? "off" : before);
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    static String str(Object o) {
        return o == null ? null : o.toString();
    }

    static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    static String pgArray(List<String> tags) {
        if (tags == null || tags.isEmpty()) return "{}";
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < tags.size(); i++) {
            if (i > 0) b.append(',');
            b.append('"').append(tags.get(i).trim().replace("\\", "").replace("\"", "")).append('"');
        }
        return b.append('}').toString();
    }
}
