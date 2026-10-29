package com.propertyos.document.service;

import com.propertyos.document.storage.FileStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Photo uploads with an AI safety check and a person in the loop.
 * <ul>
 *   <li>The AI returns a verdict and how sure it is. SAFE at or above the organisation's threshold is published at once.</li>
 *   <li>Anything else (unsafe, unsure, wrong kind of picture, or a failed check) waits in the review queue for a person.</li>
 *   <li>Only approved photos are ever served publicly or added to a property.</li>
 * </ul>
 */
@Service
public class MediaService {
    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private final Db db;
    private final ConfigClient config;
    private final FileRules rules;
    private final ServiceClient client;
    private final AuditService audit;
    private final Notifier notifier;
    private final ObjectMapper mapper;
    private final FileStore files;

    public MediaService(Db db, ConfigClient config, FileRules rules, ServiceClient client, AuditService audit, Notifier notifier, ObjectMapper mapper, FileStore files) {
        this.db = db;
        this.config = config;
        this.rules = rules;
        this.client = client;
        this.audit = audit;
        this.notifier = notifier;
        this.mapper = mapper;
        this.files = files;
    }

    /**
     * Photo columns returned by list and detail queries (leaves out the image bytes).
     */
    private static final String COLS = """
            id, kind, subject_id, subject_label, property_id, file_name, mime_type, size_bytes, status, decided_by, ai_verdict, ai_confidence, ai_category,
            ai_labels, ai_quality, ai_caption, ai_quality_issues, ai_reason, ai_provider, ai_simulated, threshold_used, flag_reason, reviewer_name, decision_note, decided_at, uploaded_by, uploaded_by_name, created_at""";

    static boolean staff(UserContext c) {
        return List.of(UserContext.SCOPE_ORG, UserContext.SCOPE_PROPERTY, UserContext.SCOPE_PLATFORM).contains(c.scope());
    }

    static boolean reviewer(UserContext c) {
        return c.has("MEDIA:REVIEW");
    }

    private int threshold() {
        int t = config.number("moderation/autoApproveConfidence", "80").intValue();
        return Math.max(1, Math.min(100, t));
    }

    /**
     * Returns the photo rules for the signed-in user's company (automatic checking on or off, approval thresholds, size limit, allowed types) and what this user may do (review, upload property photos).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> settings() {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", config.flag("ai/IMAGE_MODERATION/enabled", true));
        m.put("autoApproveConfidence", threshold());
        m.put("autoRejectEnabled", config.flag("moderation/autoRejectEnabled", false));
        m.put("autoRejectConfidence", config.number("moderation/autoRejectConfidence", "98").intValue());
        m.put("maxImageMb", config.number("moderation/maxImageMb", "8").intValue());
        m.put("allowedExtensions", allowed());
        m.put("canReview", reviewer(ctx));
        m.put("canUploadProperty", staff(ctx) && ctx.has("MEDIA:UPLOAD"));
        try {
            m.put("checker", client.get(ServiceClient.Service.AI, "/internal/ai/vision-status"));
        } catch (Exception e) {
            m.put("checker", Map.of("online", false, "provider", "unavailable"));
        }
        return m;
    }

    private List<String> allowed() {
        List<String> out = new ArrayList<>();
        config.at("moderation/allowedExtensions").forEach(n -> out.add(n.asText().toLowerCase().replace(".", "")));
        if (out.isEmpty()) out.addAll(List.of("jpg", "jpeg", "png", "webp", "gif"));
        return out;
    }

    // ---- Upload
    /**
     * Checks and stores a photo, then approves it automatically, queues it for review or rejects it according to the company's moderation settings.
     *
     * @param kind         {@code PROPERTY} or {@code PROFILE}
     * @param subjectId   the property for a property photo
     * @param originalName the uploaded file name
     * @param data         the image bytes
     * @throws ApiException (400/403/404) when the user may not add the photo or it is invalid
     */
    @Transactional
    public Map<String, Object> submit(String kind, UUID subjectId, String originalName, byte[] data) {
        UserContext ctx = UserContextHolder.require();
        kind = kind == null ? "PROPERTY" : kind.toUpperCase();
        if (!kind.equals("PROPERTY") && !kind.equals("PROFILE")) throw ApiException.badRequest("Photo type must be PROPERTY or PROFILE.");
        String label;
        UUID propertyId = null;
        if (kind.equals("PROPERTY")) {
            if (!staff(ctx) || !ctx.has("MEDIA:UPLOAD")) throw ApiException.forbidden("Only staff can add property photos.");
            if (subjectId == null) throw ApiException.badRequest("Choose the property this photo is for.");
            JsonNode p = client.get(ServiceClient.Service.LEASING, "/internal/leasing/property/" + subjectId);
            if (p == null || p.isEmpty()) throw ApiException.notFound("Property");
            if (UserContext.SCOPE_PROPERTY.equals(ctx.scope()) && (ctx.propertyIds() == null || !ctx.propertyIds().contains(subjectId)))
                throw ApiException.forbidden("This property isn't in your portfolio.");
            label = p.path("name").asText("Property");
            propertyId = subjectId;
            long count = db.value(Long.class, "select count(*) from document.media where subject_id = ? and status <> 'REJECTED'", subjectId);
            int max = config.number("moderation/maxPhotosPerProperty", "30").intValue();
            if (count >= max) throw ApiException.badRequest("This property already has " + max + " photos. Remove one before adding more.");
        } else {
            subjectId = ctx.userId();
            label = ctx.name();
            if (!ctx.has("MEDIA:UPLOAD")) throw ApiException.forbidden("You can't upload photos.");
        }
        FileRules.Checked file = rules.check(originalName, data);
        if (!allowed().contains(file.extension())) throw ApiException.badRequest("Photos must be " + String.join(", ", allowed()).toUpperCase() + " files.");
        long max = config.number("moderation/maxImageMb", "8").longValue() * 1024 * 1024;
        if (data.length > max) throw ApiException.badRequest("The photo is " + (data.length / 1024 / 1024) + " MB. The limit is " + (max / 1024 / 1024) + " MB.");
        if (db.find("select id from document.media where subject_id = ? and sha256 = ? and status <> 'REJECTED'", subjectId, file.sha256()).isPresent())
            throw ApiException.conflict("This photo has already been uploaded.");

        JsonNode verdict = moderate(kind, file, data);
        Decision d = decide(kind, verdict);
        UUID id = UUID.randomUUID();
        FileStore.Placement placed = files.put(ctx.orgId(), "photos", id, data, file.mimeType());
        List<String> labels = new ArrayList<>();
        verdict.path("labels").forEach(l -> labels.add(l.asText()));
        db.update("""
                insert into document.media (id, org_id, kind, subject_id, subject_label, property_id, file_name, mime_type, size_bytes, sha256, content, storage_key, status, decided_by,
                    ai_verdict, ai_confidence, ai_category, ai_labels, ai_reason, ai_provider, ai_simulated, threshold_used, flag_reason, decision_note, decided_at,
                    uploaded_by, uploaded_by_name, ai_quality, ai_caption, ai_quality_issues)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?::text[],?,?,?,?,?,?, case when ? in ('APPROVED','REJECTED') then now() end, ?,?,?,?,?::text[])""",
                id, ctx.orgId(), kind, subjectId, label, propertyId, file.fileName(), file.mimeType(), file.size(), file.sha256(), placed.inline(), placed.key(), d.status, d.decidedBy,
                verdict.path("verdict").asText("UNKNOWN"), verdict.path("confidence").asDouble(0), verdict.path("category").asText("OTHER"),
                "{" + String.join(",", labels.stream().map(x -> "\"" + x.replace("\\", "").replace("\"", "") + "\"").toList()) + "}",
                trim(verdict.path("reason").asText(""), 500), trim(verdict.path("provider").asText(""), 120), verdict.path("simulated").asBoolean(false), threshold(),
                d.flagReason, d.note, d.status, ctx.userId(), ctx.name(),
                verdict.path("quality").isNumber() ? verdict.path("quality").asInt() : null, trim(verdict.path("caption").asText(null), 140), pgArray(verdict.path("qualityIssues")));
        audit.record("Media", id, "PHOTO_UPLOADED", null, d.status, Map.of("kind", kind, "subject", label, "confidence", verdict.path("confidence").asDouble(0),
                "verdict", verdict.path("verdict").asText(), "threshold", threshold(), "by", ctx.name()));
        if (d.status.equals("APPROVED")) publish(id, kind, subjectId, ctx.orgId());
        else if (d.status.equals("PENDING_REVIEW")) {
            String body = ctx.name() + " uploaded a " + (kind.equals("PROFILE") ? "profile photo" : "photo for " + label) + " that needs a look: " + d.flagReason;
            notifier.send("ROLE", "PROPERTY_MANAGER", "Photo needs review", body, "/documents/photos", "NORMAL");
            notifier.send("ROLE", "ORG_ADMIN", "Photo needs review", body, "/documents/photos", "NORMAL");
        }
        return get(id);
    }

    private static String pgArray(JsonNode n) {
        List<String> v = new ArrayList<>();
        n.forEach(x -> v.add("\"" + x.asText().replace("\\", "").replace("\"", "") + "\""));
        return "{" + String.join(",", v) + "}";
    }

    private JsonNode moderate(String kind, FileRules.Checked file, byte[] data) {
        if (!config.flag("ai/IMAGE_MODERATION/enabled", true)) {
            ObjectNode o = mapper.createObjectNode();
            o.put("verdict", "UNKNOWN").put("confidence", 0).put("category", "OTHER").put("reason", "The automatic photo check is turned off, so every photo is reviewed by a person.").put("provider", "off").put("simulated", false);
            o.putArray("labels");
            return o;
        }
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("kind", kind).put("fileName", file.fileName()).put("mime", file.mimeType()).put("imageBase64", Base64.getEncoder().encodeToString(data));
            JsonNode r = UserContextHolder.runAsSystem(UserContextHolder.require().orgId(), () -> client.post(ServiceClient.Service.AI, "/internal/ai/moderate-image", body));
            if (r == null || r.isEmpty()) throw new IllegalStateException("empty reply");
            return r;
        } catch (Exception e) {
            log.warn("Photo check unavailable, sending to a person: {}", e.getMessage());
            ObjectNode o = mapper.createObjectNode();
            o.put("verdict", "UNKNOWN").put("confidence", 0).put("category", "OTHER").put("reason", "The automatic check was not available.").put("provider", "unavailable").put("simulated", false);
            o.putArray("labels");
            return o;
        }
    }

    record Decision(String status, String decidedBy, String flagReason, String note) {
    }

    Decision decide(String kind, JsonNode v) {
        String verdict = v.path("verdict").asText("UNKNOWN");
        double pct = v.path("confidence").asDouble(0) * 100;
        String category = v.path("category").asText("OTHER");
        int threshold = threshold();
        String expected = kind.equals("PROFILE") ? "PERSON_PHOTO" : "PROPERTY";
        if (verdict.equals("SAFE") && category.equals(expected) && pct >= threshold)
            return new Decision("APPROVED", "AI", null, "Approved automatically: " + Math.round(pct) + "% confident, threshold " + threshold + "%.");
        if (verdict.equals("UNSAFE") && config.flag("moderation/autoRejectEnabled", false) && pct >= config.number("moderation/autoRejectConfidence", "98").doubleValue())
            return new Decision("REJECTED", "AI", "Unsafe", "Rejected automatically: " + Math.round(pct) + "% confident the photo is unsafe.");
        String why;
        if (verdict.equals("UNSAFE")) why = "Flagged as " + category.toLowerCase().replace('_', ' ') + " (" + Math.round(pct) + "% sure)";
        else if (verdict.equals("UNKNOWN")) why = "The automatic check could not decide";
        else if (!category.equals(expected)) why = "Does not look like a " + (kind.equals("PROFILE") ? "profile photo" : "property photo");
        else why = "Only " + Math.round(pct) + "% confident, below the " + threshold + "% needed to publish automatically";
        return new Decision("PENDING_REVIEW", null, why, null);
    }

    // ---- Publishing
    private String publicUrl(UUID id) {
        String base = System.getenv("PUBLIC_API_URL");
        if (base == null || base.isBlank()) base = "http://localhost:8080";
        return base.replaceAll("/$", "") + "/api/documents/public/media/" + id;
    }

    private void publish(UUID id, String kind, UUID subjectId, UUID orgId) {
        if (!kind.equals("PROPERTY")) return;
        ObjectNode b = mapper.createObjectNode();
        b.put("url", publicUrl(id));
        UserContextHolder.runAsSystem(orgId, () -> client.post(ServiceClient.Service.LEASING, "/internal/leasing/property/" + subjectId + "/photo", b));
    }

    private void unpublish(UUID id, String kind, UUID subjectId, UUID orgId) {
        if (!kind.equals("PROPERTY")) return;
        ObjectNode b = mapper.createObjectNode();
        b.put("url", publicUrl(id));
        try {
            UserContextHolder.runAsSystem(orgId, () -> client.post(ServiceClient.Service.LEASING, "/internal/leasing/property/" + subjectId + "/photo/remove", b));
        } catch (Exception e) {
            log.warn("Could not remove photo {} from property: {}", id, e.getMessage());
        }
    }

    // ---- Review
    /**
     * Approves or rejects a photo. An approved photo is published and a rejected one that was live is taken down.
     *
     * @param decision {@code APPROVE} or {@code REJECT}
     * @param note     why; required for a rejection
     * @throws ApiException (403) for non-reviewers, (400) for a bad decision or missing reason, (409) if already in that state
     */
    @Transactional
    public Map<String, Object> decide(UUID id, String decision, String note) {
        UserContext ctx = UserContextHolder.require();
        if (!reviewer(ctx)) throw ApiException.forbidden("You can't review photos.");
        Map<String, Object> m = db.find("select id, org_id, kind, subject_id, status, uploaded_by, subject_label from document.media where id = ?", id).orElseThrow(() -> ApiException.notFound("Photo"));
        boolean approve = "APPROVE".equalsIgnoreCase(decision);
        if (!approve && !"REJECT".equalsIgnoreCase(decision)) throw ApiException.badRequest("Decision must be APPROVE or REJECT.");
        String before = (String) m.get("status");
        String after = approve ? "APPROVED" : "REJECTED";
        if (before.equals(after)) throw ApiException.conflict("This photo is already " + after.toLowerCase() + ".");
        if (!approve && (note == null || note.isBlank())) throw ApiException.badRequest("Add a short reason so the uploader knows what to change.");
        db.update("update document.media set status = ?, decided_by = 'PERSON', reviewer_id = ?, reviewer_name = ?, decision_note = ?, decided_at = now() where id = ?",
                after, ctx.userId(), ctx.name(), trim(note, 500), id);
        UUID org = (UUID) m.get("org_id"), subject = (UUID) m.get("subject_id");
        String kind = (String) m.get("kind");
        if (approve) publish(id, kind, subject, org);
        else if (before.equals("APPROVED")) unpublish(id, kind, subject, org);
        audit.record("Media", id, approve ? "PHOTO_APPROVED" : "PHOTO_REJECTED", before, after, Map.of("by", ctx.name(), "note", note == null ? "" : note));
        if (config.flag("moderation/notifyUploaderOnDecision", true) && !m.get("uploaded_by").equals(ctx.userId()))
            notifier.send("USER", m.get("uploaded_by").toString(), approve ? "Photo approved" : "Photo not approved",
                    approve ? "Your photo for " + m.get("subject_label") + " is now published." : "Your photo for " + m.get("subject_label") + " wasn't approved: " + note, "/documents/photos", "NORMAL");
        return get(id);
    }

    /**
     * Removes a photo and unpublishes it if it was live.
     */
    @Transactional
    public void remove(UUID id) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> m = db.find("select id, org_id, kind, subject_id, status, uploaded_by, storage_key from document.media where id = ?", id).orElseThrow(() -> ApiException.notFound("Photo"));
        if (!reviewer(ctx) && !ctx.userId().equals(m.get("uploaded_by"))) throw ApiException.forbidden("You can only remove your own photos.");
        if ("APPROVED".equals(m.get("status"))) unpublish(id, (String) m.get("kind"), (UUID) m.get("subject_id"), (UUID) m.get("org_id"));
        db.update("delete from document.media where id = ?", id);
        files.delete((String) m.get("storage_key"));
        audit.record("Media", id, "PHOTO_REMOVED", (String) m.get("status"), "REMOVED", Map.of("by", ctx.name()));
    }

    // ---- Reads
    /**
     * Returns one photo, if the user may see it.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> r = db.find("select " + COLS + " from document.media where id = ?", id).orElseThrow(() -> ApiException.notFound("Photo"));
        if (!reviewer(ctx) && !ctx.userId().equals(r.get("uploaded_by"))) throw ApiException.notFound("Photo");
        return view(r);
    }

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>(r);
        m.put("publicUrl", "APPROVED".equals(r.get("status")) ? publicUrl((UUID) r.get("id")) : null);
        return m;
    }

    /**
     * Lists photos the user may see, filtered by status or subject.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> list(Map<String, String> f) {
        UserContext ctx = UserContextHolder.require();
        boolean all = reviewer(ctx) && !"mine".equals(f.get("scope"));
        StringBuilder sql = new StringBuilder("select " + COLS + " from document.media where 1=1");
        List<Object> args = new ArrayList<>();
        if (!all) {
            sql.append(" and uploaded_by = ?");
            args.add(ctx.userId());
        }
        if (f.get("status") != null && !f.get("status").isBlank()) {
            sql.append(" and status = ?");
            args.add(f.get("status"));
        }
        if (f.get("kind") != null && !f.get("kind").isBlank()) {
            sql.append(" and kind = ?");
            args.add(f.get("kind"));
        }
        if (f.get("subjectId") != null && !f.get("subjectId").isBlank()) {
            sql.append(" and subject_id = ?");
            args.add(UUID.fromString(f.get("subjectId")));
        }
        sql.append(" order by case status when 'PENDING_REVIEW' then 0 else 1 end, created_at desc limit 200");
        List<Map<String, Object>> rows = db.list(sql.toString(), args.toArray());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", rows.stream().map(this::view).toList());
        if (all) {
            Map<String, Object> s = db.one("Photos", """
                    select count(*) filter (where status = 'PENDING_REVIEW') as pending,
                           count(*) filter (where status = 'APPROVED' and decided_by = 'AI') as auto_approved,
                           count(*) filter (where status = 'APPROVED' and decided_by = 'PERSON') as person_approved,
                           count(*) filter (where status = 'REJECTED') as rejected,
                           count(*) as total from document.media""");
            out.put("summary", s);
        }
        return out;
    }


    /**
     * Reads a photo's image bytes after checking the user may see it.
     */
    @Transactional(readOnly = true)
    public DocumentService.FileData content(UUID id) {
        UserContext ctx = UserContextHolder.require();
        Map<String, Object> r = db.find("select file_name, mime_type, content, storage_key, uploaded_by from document.media where id = ?", id).orElseThrow(() -> ApiException.notFound("Photo"));
        if (!reviewer(ctx) && !ctx.userId().equals(r.get("uploaded_by"))) throw ApiException.notFound("Photo");
        return new DocumentService.FileData((String) r.get("file_name"), (String) r.get("mime_type"), files.read((String) r.get("storage_key"), (byte[]) r.get("content")));
    }

    /** Public: approved photos only. */
    @Transactional(readOnly = true)
    public Optional<DocumentService.FileData> publicContent(UUID id) {
        return db.find("select file_name, mime_type, content, storage_key from document.media where id = ? and status = 'APPROVED'", id)
                .map(r -> new DocumentService.FileData((String) r.get("file_name"), (String) r.get("mime_type"), files.read((String) r.get("storage_key"), (byte[]) r.get("content"))));
    }

    private static String trim(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }
}
