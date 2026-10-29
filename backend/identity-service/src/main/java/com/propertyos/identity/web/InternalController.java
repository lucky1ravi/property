package com.propertyos.identity.web;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.rls.Tx;
import com.propertyos.identity.service.ConfigService;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** Service-to-service endpoints (internal network + service token only). */
@RestController
@RequestMapping("/internal")
public class InternalController {
    private final ConfigService config;
    private final IdentityStore store;
    private final Tx tx;

    public InternalController(ConfigService config, IdentityStore store, Tx tx) {
        this.config = config;
        this.store = store;
        this.tx = tx;
    }

    @GetMapping("/config/{orgId}")
    public ObjectNode config(@PathVariable UUID orgId) {
        return config.effective(orgId);
    }

    /** Resolves a notification audience to user ids: ROLE (key or name), PARTY or PROPERTY. */
    @GetMapping("/audience")
    public List<UUID> audience(@RequestParam String type, @RequestParam String target) {
        return tx.read(() -> switch (type) {
            case "ROLE" -> store.usersWithRoleKeyOrName(target);
            case "PARTY" -> store.usersForParty(UUID.fromString(target));
            case "PROPERTY" -> store.usersForProperty(UUID.fromString(target));
            case "USER" -> List.of(UUID.fromString(target));
            default -> List.of();
        });
    }

    @GetMapping("/users/{id}")
    public Map<String, Object> user(@PathVariable UUID id) {
        return tx.read(() -> store.user(id).map(u -> Map.<String, Object>of("id", u.get("id"), "name", u.get("name"),
                "email", u.get("email"))).orElse(Map.of()));
    }

    /** Active people in the organisation, for deciding who may message whom. */
    @GetMapping("/directory")
    public List<Map<String, Object>> directory() {
        return tx.read(() -> store.users().stream().filter(u -> "ACTIVE".equals(String.valueOf(u.get("status"))))
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", u.get("id")); m.put("name", u.get("name")); m.put("title", u.get("title")); m.put("persona", u.get("persona"));
                    m.put("party_type", u.get("party_type")); m.put("party_id", u.get("party_id"));
                    m.put("roles", String.valueOf(u.get("roles"))); m.put("property_ids", String.valueOf(u.get("property_ids")));
                    return m;
                }).toList());
    }

    @GetMapping("/orgs")
    public List<Map<String, Object>> orgs() {
        return tx.read(store::orgs);
    }

    /** Brokers assigned to a property, for showing assignment and capacity balancing. */
    @GetMapping("/brokers")
    public List<Map<String, Object>> brokers() {
        return tx.read(() -> store.users().stream()
                .filter(u -> String.valueOf(u.get("roles")).contains("\"Broker\""))
                .map(u -> Map.<String, Object>of("id", u.get("id"), "name", u.get("name"), "propertyIds", String.valueOf(u.get("property_ids"))))
                .toList());
    }
}
