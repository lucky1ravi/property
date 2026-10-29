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
    /**
     * Configuration sections a company may change.
     */
    public static final Set<String> SECTIONS = Set.of("features", "fees", "invoicing", "approvals", "screening", "sla",
            "renewals", "ai", "notifications", "branding", "stateRules", "payments", "alerts", "chat", "documents", "moderation", "templates", "customFields");

    private final IdentityStore store;
    private final ConfigClient configClient;
    private final PlanService planService;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final EventPublisher events;

    public ConfigService(IdentityStore store, ConfigClient configClient, PlanService planService, ObjectMapper mapper, AuditService audit, EventPublisher events) {
        this.planService = planService;
        this.store = store;
        this.configClient = configClient;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
    }

    /**
     * Returns only the settings a company has changed.
     */
    @Transactional(readOnly = true)
    public ObjectNode overrides(UUID orgId) {
        return store.config(orgId).map(m -> (ObjectNode) read((String) m.get("config"))).orElse(mapper.createObjectNode());
    }

    /**
     * Returns the settings in force: defaults with the company's changes merged over them.
     */
    @Transactional(readOnly = true)
    public ObjectNode effective(UUID orgId) {
        ObjectNode result = configClient.defaults().deepCopy();
        result.set("plans", planService.asConfig());
        store.productDefaults().forEach((section, json) -> {
            if (SECTIONS.contains(section)) result.set(section, read(json)); // the operator's product-wide defaults
        });
        ObjectNode over = overrides(orgId);
        merge(result, over);
        JsonNode brand = result.get("branding");
        if (brand instanceof ObjectNode b && b.path("companyName").asText("").isBlank()) {
            store.org(orgId).ifPresent(o -> b.put("companyName", String.valueOf(o.get("name")))); // a company is shown under its own name
        }
        result.set("plans", planService.asConfig()); // plans are the operator's, never a company's to override
        String plan = store.org(orgId).map(o -> (String) o.get("plan")).orElse("ENTERPRISE");
        applyPlan(result, plan, store.org(orgId).map(o -> read(String.valueOf(o.get("entitlements")))).orElse(mapper.createObjectNode()));
        result.put("plan", plan);
        result.put("version", store.config(orgId).map(m -> ((Number) m.get("version")).intValue()).orElse(0));
        return result;
    }

    /** Features outside the plan are switched off, whatever the organisation override says. */
    private void applyPlan(ObjectNode cfg, String plan, JsonNode entitlements) {
        JsonNode planFeatures = cfg.at("/plans/" + plan + "/features");
        Set<String> allowed = new HashSet<>();
        if (planFeatures.isArray()) planFeatures.forEach(n -> allowed.add(n.asText()));
        boolean all = allowed.contains("*");
        ObjectNode features = (ObjectNode) cfg.get("features");
        ObjectNode gated = mapper.createObjectNode();
        features.fieldNames().forEachRemaining(f -> {
            JsonNode e = entitlements == null ? null : entitlements.get(f);
            if (e != null && e.isBoolean()) {
                if (!e.asBoolean()) gated.put(f, "Turned off for your company by PropertyOS");
            } else if (!all && !allowed.contains(f)) gated.put(f, "Not in " + plan + " plan");
        });
        gated.fieldNames().forEachRemaining(f -> features.put(f, false));
        cfg.set("planGated", gated);
    }

    /** What a platform operator can set for one company: each feature's plan default, the override, and what the company ends up with. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> featureMatrix(UUID orgId) {
        ObjectNode defaults = configClient.defaults().deepCopy();
        defaults.set("plans", planService.asConfig());
        String plan = store.org(orgId).map(o -> (String) o.get("plan")).orElseThrow(() -> ApiException.notFound("Company"));
        JsonNode ent = read(String.valueOf(store.org(orgId).get().get("entitlements")));
        JsonNode planFeatures = defaults.at("/plans/" + plan + "/features");
        Set<String> inPlan = new HashSet<>();
        if (planFeatures.isArray()) planFeatures.forEach(n -> inPlan.add(n.asText()));
        ObjectNode eff = effective(orgId);
        List<Map<String, Object>> out = new ArrayList<>();
        defaults.get("features").fieldNames().forEachRemaining(f -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("feature", f);
            m.put("inPlan", inPlan.contains("*") || inPlan.contains(f));
            m.put("override", ent.has(f) && ent.get(f).isBoolean() ? ent.get(f).asBoolean() : null);
            m.put("enabled", eff.path("features").path(f).asBoolean(false));
            m.put("companyTurnedOff", false);
            out.add(m);
        });
        return out;
    }

    /**
     * Sets feature overrides for a company (platform administrators).
     *
     * @param orgId   the company
     * @param changes feature key to on/off/default
     * @return the updated feature matrix
     */
    @Transactional
    public List<Map<String, Object>> setEntitlements(UUID orgId, Map<String, Object> changes) {
        Set<String> known = new HashSet<>();
        configClient.defaults().get("features").fieldNames().forEachRemaining(known::add);
        ObjectNode ent = (ObjectNode) read(String.valueOf(store.org(orgId).orElseThrow(() -> ApiException.notFound("Company")).get("entitlements")));
        for (Map.Entry<String, Object> e : changes.entrySet()) {
            if (!known.contains(e.getKey())) throw ApiException.badRequest("Unknown feature: " + e.getKey());
            if (e.getValue() == null) ent.remove(e.getKey());
            else if (e.getValue() instanceof Boolean b) ent.put(e.getKey(), b);
            else throw ApiException.badRequest("Use true, false or null for " + e.getKey());
        }
        store.setEntitlements(orgId, ent.toString());
        audit.record("Organization", orgId, "ORG_FEATURES_CHANGED", null, null, Map.of("changes", changes.toString(), "by", UserContextHolder.require().name()));
        events.publish("ConfigurationChanged", "Organization", orgId, Map.of("section", "features", "version", 0));
        configClient.invalidate(orgId);
        return featureMatrix(orgId);
    }

    /**
     * Replaces one configuration section, saves a new version and clears the cache.
     *
     * @param section one of {@link #SECTIONS}
     * @param value   the new section
     * @param summary note for the history, or null
     * @return the effective configuration
     * @throws ApiException (400) for an unknown section
     */
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

    /**
     * Restores an earlier version as a new version.
     *
     * @param version the version to restore
     * @throws ApiException (404) when the version does not exist
     */
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

    /**
     * Lists recent configuration versions for the signed-in user's company.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history() {
        return store.configHistory(UserContextHolder.orgId());
    }

    /**
     * Stores a new company's starting configuration.
     *
     * @param orgId     the company
     * @param overrides settings that differ from the defaults
     * @param by        who created it
     */
    @Transactional
    public void initialise(UUID orgId, ObjectNode overrides, String by) {
        store.saveConfig(orgId, overrides.toString(), "*", "Organisation onboarded", by);
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
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
