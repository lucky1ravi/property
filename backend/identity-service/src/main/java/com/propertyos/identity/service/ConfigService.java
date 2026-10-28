package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Product configuration: platform defaults, gated by the organisation's plan, overridden
 * by organisation settings edited in the UI. Every change is versioned and can be rolled back.
 */
@Service
public class ConfigService {
    public static final Set<String> SECTIONS = Set.of("features", "fees", "invoicing", "approvals", "screening", "sla",
            "renewals", "ai", "notifications", "branding", "stateRules", "templates", "customFields");

    private final IdentityStore store;
    private final ConfigClient configClient;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final EventPublisher events;

    public ConfigService(IdentityStore store, ConfigClient configClient, ObjectMapper mapper, AuditService audit, EventPublisher events) {
        this.store = store;
        this.configClient = configClient;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
    }

    @Transactional(readOnly = true)
    public ObjectNode overrides(UUID orgId) {
        return store.config(orgId).map(m -> (ObjectNode) read((String) m.get("config"))).orElse(mapper.createObjectNode());
    }

    @Transactional(readOnly = true)
    public ObjectNode effective(UUID orgId) {
        ObjectNode result = configClient.defaults().deepCopy();
        ObjectNode over = overrides(orgId);
        merge(result, over);
        String plan = store.org(orgId).map(o -> (String) o.get("plan")).orElse("ENTERPRISE");
        applyPlan(result, plan);
        result.put("plan", plan);
        result.put("version", store.config(orgId).map(m -> ((Number) m.get("version")).intValue()).orElse(0));
        return result;
    }

    /** Features outside the plan are switched off, whatever the organisation override says. */
    private void applyPlan(ObjectNode cfg, String plan) {
        JsonNode planFeatures = cfg.at("/plans/" + plan + "/features");
        if (!planFeatures.isArray()) return;
        Set<String> allowed = new HashSet<>();
        planFeatures.forEach(n -> allowed.add(n.asText()));
        if (allowed.contains("*")) return;
        ObjectNode features = (ObjectNode) cfg.get("features");
        ObjectNode gated = mapper.createObjectNode();
        features.fieldNames().forEachRemaining(f -> {
            if (!allowed.contains(f)) gated.put(f, "Not in " + plan + " plan");
        });
        gated.fieldNames().forEachRemaining(f -> features.put(f, false));
        cfg.set("planGated", gated);
    }

    @Transactional
    public ObjectNode updateSection(String section, JsonNode value, String summary) {
        if (!SECTIONS.contains(section)) throw ApiException.badRequest("Unknown configuration section: " + section);
        UUID orgId = UserContextHolder.orgId();
        ObjectNode over = overrides(orgId);
        JsonNode before = over.get(section);
        over.set(section, value);
        String by = UserContextHolder.require().name();
        int version = store.saveConfig(orgId, over.toString(), section,
                summary == null || summary.isBlank() ? "Updated " + section : summary, by);
        audit.record("Configuration", orgId, "CONFIG_UPDATED", "v" + (version - 1), "v" + version,
                Map.of("section", section, "before", before == null ? "default" : before.toString(), "after", value.toString()));
        events.publish("ConfigurationChanged", "Organization", orgId, Map.of("section", section, "version", version));
        configClient.invalidate(orgId);
        return effective(orgId);
    }

    @Transactional
    public ObjectNode rollback(int version) {
        UUID orgId = UserContextHolder.orgId();
        String json = store.configAtVersion(orgId, version).orElseThrow(() -> ApiException.notFound("Configuration version " + version));
        int newVersion = store.saveConfig(orgId, json, "*", "Rolled back to version " + version, UserContextHolder.require().name());
        audit.record("Configuration", orgId, "CONFIG_ROLLBACK", null, "v" + newVersion, Map.of("restoredVersion", version));
        events.publish("ConfigurationChanged", "Organization", orgId, Map.of("section", "*", "version", newVersion));
        configClient.invalidate(orgId);
        return effective(orgId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history() {
        return store.configHistory(UserContextHolder.orgId());
    }

    @Transactional
    public void initialise(UUID orgId, ObjectNode overrides, String by) {
        store.saveConfig(orgId, overrides.toString(), "*", "Organisation onboarded", by);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    /** Deep merge: objects merge recursively; arrays and scalars replace. */
    static void merge(ObjectNode target, JsonNode source) {
        if (source == null || !source.isObject()) return;
        source.fields().forEachRemaining(e -> {
            JsonNode existing = target.get(e.getKey());
            if (existing != null && existing.isObject() && e.getValue().isObject()) {
                merge((ObjectNode) existing, e.getValue());
            } else if (e.getValue() instanceof ArrayNode || !e.getValue().isObject() || existing == null) {
                target.set(e.getKey(), e.getValue().deepCopy());
            } else {
                target.set(e.getKey(), e.getValue().deepCopy());
            }
        });
    }
}
