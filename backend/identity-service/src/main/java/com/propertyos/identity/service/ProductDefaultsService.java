package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Settings the PropertyOS operator sets once for the whole product. Each company starts from these and can still
 * change the ones it is allowed to. Company-specific sections (branding, state rules) are not offered here.
 */
@Service
public class ProductDefaultsService {
    /** Sections that make sense for every company at once. */
    public static final Set<String> SECTIONS = Set.of("features", "fees", "invoicing", "approvals", "screening", "sla", "renewals",
            "ai", "notifications", "payments", "alerts", "chat", "documents", "moderation");

    private final IdentityStore store;
    private final ConfigClient configClient;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final EventPublisher events;

    public ProductDefaultsService(IdentityStore store, ConfigClient configClient, ObjectMapper mapper, AuditService audit, EventPublisher events) {
        this.store = store;
        this.configClient = configClient;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
    }

    /** Built-in defaults with the operator's changes on top, plus which sections the operator has changed. */
    public Map<String, Object> view() {
        ObjectNode base = configClient.defaults().deepCopy();
        Map<String, String> changed = store.productDefaults();
        ObjectNode values = mapper.createObjectNode();
        for (String s : SECTIONS) {
            if (changed.containsKey(s)) values.set(s, read(changed.get(s)));
            else if (base.has(s)) values.set(s, base.get(s));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("values", values);
        out.put("changed", changed.keySet().stream().filter(SECTIONS::contains).sorted().toList());
        out.put("companies", store.orgIds().size() - 1);
        return out;
    }

    /** Sets one section for every company that has not changed it itself. */
    public Map<String, Object> save(String section, JsonNode value) {
        check(section);
        if (value == null || !value.isObject()) throw ApiException.badRequest("Send the section as an object.");
        store.saveProductDefault(section, value.toString(), UserContextHolder.require().name());
        audit.record("ProductDefaults", UUID.nameUUIDFromBytes(section.getBytes()), "PRODUCT_DEFAULT_UPDATED", null, null, Map.of("after", value.toString()));
        refreshAll(section);
        return view();
    }

    /** Puts a section back to the built-in default. */
    public Map<String, Object> reset(String section) {
        check(section);
        store.deleteProductDefault(section);
        audit.record("ProductDefaults", UUID.nameUUIDFromBytes(section.getBytes()), "PRODUCT_DEFAULT_RESET", null, null, Map.of());
        refreshAll(section);
        return view();
    }

    private void check(String section) {
        if (!SECTIONS.contains(section)) throw ApiException.badRequest("This setting is set per company, not for the whole product: " + section);
    }

    private void refreshAll(String section) {
        for (UUID org : store.orgIds()) {
            configClient.invalidate(org);
            events.publish("ConfigurationChanged", "Organization", org, Map.of("section", section, "version", 0));
        }
    }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
