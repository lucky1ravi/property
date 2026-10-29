package com.propertyos.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.*;

/**
 * Required-document checklists. Each company decides which documents an application, lease, vendor or owner needs
 * (configuration section "documents/requirements"); this service says which of them are on file, waiting, expired or missing.
 */
@Service
public class DocumentRequirements {
    private final Db db;
    private final ConfigClient config;
    private final Lookups lookups;

    public DocumentRequirements(Db db, ConfigClient config, Lookups lookups) {
        this.db = db;
        this.config = config;
        this.lookups = lookups;
    }


    /** Which required documents a record has and which are missing, per the organisation's checklist. */
    @Transactional(readOnly = true)
    public Map<String, Object> requirements(String entityType, UUID entityId) {
        List<String> required = new ArrayList<>();
        config.at("documents/requirements/" + entityType).forEach(n -> required.add(n.asText()));
        List<Map<String, Object>> docs = db.list("select id, title, doc_type, review_status, expires_on, signature_status from document.document where entity_type = ? and entity_id = ? and status = 'ACTIVE'", entityType, entityId);
        if ("LEASE".equals(entityType)) {
            // Renters insurance and inspections can sit on the resident rather than the lease.
            Optional<Map<String, Object>> t = db.find("select tenant_id from document.document where entity_type = 'LEASE' and entity_id = ? and tenant_id is not null limit 1", entityId);
            t.ifPresent(x -> docs.addAll(db.list("select id, title, doc_type, review_status, expires_on, signature_status from document.document where tenant_id = ? and status = 'ACTIVE' and (entity_id is distinct from ?)", x.get("tenant_id"), entityId)));
        }
        if ("VENDOR".equals(entityType))
            docs.addAll(db.list("select id, title, doc_type, review_status, expires_on, signature_status from document.document where vendor_id = ? and status = 'ACTIVE' and (entity_id is distinct from ?) and category in ('TAX','INSURANCE','CONTRACT')", entityId, entityId));
        if ("OWNER".equals(entityType))
            docs.addAll(db.list("select id, title, doc_type, review_status, expires_on, signature_status from document.document where owner_id = ? and status = 'ACTIVE' and (entity_id is distinct from ?) and category in ('TAX','CONTRACT')", entityId, entityId));
        List<Map<String, Object>> items = new ArrayList<>();
        int missing = 0;
        for (String type : required) {
            Map<String, Object> match = null;
            for (Map<String, Object> d : docs) {
                if (!type.equalsIgnoreCase(String.valueOf(d.get("doc_type"))) || "REJECTED".equals(d.get("review_status"))) continue;
                if ("PENDING".equals(String.valueOf(d.get("signature_status"))) || "PARTIAL".equals(String.valueOf(d.get("signature_status")))) { if (match == null) match = d; continue; }
                match = d;
                break;
            }
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("type", type);
            String state;
            if (match == null) state = "MISSING";
            else if (match.get("expires_on") != null && LocalDate.parse(match.get("expires_on").toString()).isBefore(LocalDate.now())) state = "EXPIRED";
            else if (List.of("PENDING", "PARTIAL").contains(String.valueOf(match.get("signature_status")))) state = "AWAITING_SIGNATURE";
            else if ("PENDING".equals(match.get("review_status"))) state = "IN_REVIEW";
            else state = "ON_FILE";
            if (!"ON_FILE".equals(state) && !"IN_REVIEW".equals(state) && !"AWAITING_SIGNATURE".equals(state)) missing++;
            i.put("state", state);
            if (match != null) { i.put("documentId", match.get("id")); i.put("title", match.get("title")); }
            items.add(i);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("entityType", entityType);
        out.put("entityId", entityId);
        out.put("items", items);
        out.put("missing", missing);
        out.put("complete", missing == 0);
        return out;
    }

    /** Records with required documents missing, expired or waiting for review, across applications, leases, vendors and owners. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> requirementGaps() {
        UserContext ctx = UserContextHolder.require();
        if (!DocumentService.staff(ctx)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.function.Predicate<String> inScope = pid -> !UserContext.SCOPE_PROPERTY.equals(ctx.scope()) || (pid != null && ctx.propertyIds() != null && ctx.propertyIds().stream().anyMatch(x -> x.toString().equals(pid)));
        record Src(String type, ServiceClient.Service svc, String path, String label, String link) { }
        List<Src> sources = List.of(
                new Src("APPLICATION", ServiceClient.Service.LEASING, "/internal/leasing/applications-brief", "label", "/leasing/applications/"),
                new Src("LEASE", ServiceClient.Service.LEASING, "/internal/leasing/leases-brief", "label", "/leasing/leases/"),
                new Src("VENDOR", ServiceClient.Service.MAINTENANCE, "/internal/maintenance/vendors-brief", "label", "/maintenance/vendors?id="),
                new Src("OWNER", ServiceClient.Service.LEASING, "/internal/leasing/owners-brief", "label", "/leasing/owners?id="));
        for (Src s : sources) {
            if (!config.at("documents/requirements/" + s.type()).isArray() || config.at("documents/requirements/" + s.type()).isEmpty()) continue;
            if ("OWNER".equals(s.type()) && UserContext.SCOPE_PROPERTY.equals(ctx.scope())) continue;
            for (JsonNode e : lookups.list(s.svc(), s.path())) {
                if (!"VENDOR".equals(s.type()) && !inScope.test(e.path("property_id").asText(null))) continue;
                UUID id = UUID.fromString(e.path("id").asText());
                Map<String, Object> r = requirements(s.type(), id);
                List<Map<String, Object>> gaps = new ArrayList<>();
                for (Object o : (List<?>) r.get("items")) {
                    @SuppressWarnings("unchecked") Map<String, Object> i = (Map<String, Object>) o;
                    if (!"ON_FILE".equals(i.get("state"))) gaps.add(i);
                }
                if (gaps.isEmpty()) continue;
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("entityType", s.type());
                row.put("entityId", id);
                row.put("label", e.path(s.label()).asText());
                row.put("status", e.path("status").asText(null));
                row.put("propertyId", e.path("property_id").asText(null));
                row.put("link", s.link() + id);
                row.put("missing", r.get("missing"));
                row.put("items", gaps);
                out.add(row);
            }
        }
        out.sort(Comparator.comparing((Map<String, Object> x) -> -((Number) x.get("missing")).intValue()));
        return out;
    }
}
