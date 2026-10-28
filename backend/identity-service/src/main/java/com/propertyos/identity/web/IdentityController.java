package com.propertyos.identity.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.catalog.FeatureCatalog;
import com.propertyos.identity.service.AccessAdminService;
import com.propertyos.identity.service.AuthService;
import com.propertyos.identity.service.ConfigService;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.jdbc.core.JdbcTemplate;
import com.propertyos.common.rls.Tx;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/identity")
public class IdentityController {
    private final AuthService auth;
    private final AccessAdminService access;
    private final ConfigService config;
    private final IdentityStore store;
    private final AuditService audit;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;
    private final Tx tx;

    public IdentityController(AuthService auth, AccessAdminService access, ConfigService config, IdentityStore store,
                              AuditService audit, EventPublisher events, JdbcTemplate jdbc, Tx tx) {
        this.tx = tx;
        this.auth = auth;
        this.access = access;
        this.config = config;
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.jdbc = jdbc;
    }

    /** Current user, permissions, data scope, enabled features and branding. */
    @GetMapping("/me")
    public Map<String, Object> me() {
        UserContext ctx = UserContextHolder.require();
        ObjectNode cfg = config.effective(ctx.orgId());
        Map<String, Object> org = transactional(() -> store.org(ctx.orgId()).orElse(Map.of()));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", ctx.userId());
        m.put("name", ctx.name());
        m.put("email", ctx.email());
        m.put("roles", ctx.roles());
        m.put("permissions", new TreeSet<>(ctx.permissions()));
        m.put("scope", ctx.scope());
        m.put("partyId", ctx.partyId());
        m.put("propertyIds", ctx.propertyIds());
        m.put("limits", ctx.limits());
        m.put("fieldRules", ctx.fieldRules());
        m.put("organization", Map.of("id", ctx.orgId(), "name", org.getOrDefault("name", ""), "plan", org.getOrDefault("plan", ""),
                "state", org.getOrDefault("state", "")));
        m.put("features", cfg.get("features"));
        m.put("branding", cfg.get("branding"));
        return m;
    }

    /** Re-issues a token with the latest roles and permissions (after an admin change). */
    @PostMapping("/refresh")
    public Map<String, Object> refresh() {
        return auth.issue(UserContextHolder.require().userId(), "REFRESH");
    }

    // ---------------- users ----------------
    @GetMapping("/users")
    @RequiresPermission("USER:VIEW")
    public List<Map<String, Object>> users() {
        return access.users();
    }

    @PostMapping("/users")
    @RequiresPermission("USER:CREATE")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        return access.createUser(body);
    }

    @PutMapping("/users/{id}")
    @RequiresPermission("USER:EDIT")
    public void updateUser(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        access.updateUser(id, body);
    }

    @DeleteMapping("/users/{id}")
    @RequiresPermission("USER:DELETE")
    public void deleteUser(@PathVariable UUID id) {
        access.deleteUser(id);
    }

    // ---------------- roles and permission matrix ----------------
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of("features", FeatureCatalog.FEATURES, "actions", FeatureCatalog.ACTIONS,
                "scopes", FeatureCatalog.SCOPES, "fields", FeatureCatalog.FIELDS);
    }

    @GetMapping("/roles")
    @RequiresPermission({"ROLE:VIEW", "USER:VIEW"})
    public List<Map<String, Object>> roles() {
        return access.roles();
    }

    @PostMapping("/roles")
    @RequiresPermission("ROLE:CONFIGURE")
    public Map<String, Object> createRole(@RequestBody Map<String, Object> body) {
        return access.createRole(body);
    }

    @PutMapping("/roles/{id}")
    @RequiresPermission("ROLE:CONFIGURE")
    public void updateRole(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        access.updateRole(id, body);
    }

    @PutMapping("/roles/{id}/permissions")
    @RequiresPermission("ROLE:CONFIGURE")
    public Map<String, Object> setPermissions(@PathVariable UUID id, @RequestBody List<Map<String, Object>> grants) {
        return access.setPermissions(id, grants);
    }

    @DeleteMapping("/roles/{id}")
    @RequiresPermission("ROLE:CONFIGURE")
    public void deleteRole(@PathVariable UUID id) {
        access.deleteRole(id);
    }

    // ---------------- configuration ----------------
    @GetMapping("/config")
    public ObjectNode config() {
        return config.effective(UserContextHolder.orgId());
    }

    @GetMapping("/config/overrides")
    @RequiresPermission("SETTINGS:VIEW")
    public ObjectNode overrides() {
        return config.overrides(UserContextHolder.orgId());
    }

    @PutMapping("/config/{section}")
    @RequiresPermission("SETTINGS:CONFIGURE")
    public ObjectNode updateConfig(@PathVariable String section, @RequestBody JsonNode body,
                                   @RequestParam(required = false) String summary) {
        return config.updateSection(section, body, summary);
    }

    @GetMapping("/config/history")
    @RequiresPermission("SETTINGS:VIEW")
    public List<Map<String, Object>> history() {
        return config.history();
    }

    @PostMapping("/config/rollback/{version}")
    @RequiresPermission("SETTINGS:CONFIGURE")
    public ObjectNode rollback(@PathVariable int version) {
        return config.rollback(version);
    }

    // ---------------- audit ----------------
    @GetMapping("/audit/{entityId}")
    public List<Map<String, Object>> timeline(@PathVariable UUID entityId) {
        return transactional(() -> audit.timeline(entityId));
    }

    @GetMapping("/audit")
    @RequiresPermission("AUDIT:VIEW")
    public List<Map<String, Object>> recent(@RequestParam(defaultValue = "100") int limit,
                                            @RequestParam(required = false) String entityType) {
        int n = Math.min(Math.max(limit, 1), 500);
        return transactional(() -> entityType == null
                ? jdbc.queryForList("select id, entity_type, entity_id, action, from_state, to_state, actor_name, details::text as details, created_at " +
                "from platform.audit_event order by id desc limit ?", n)
                : jdbc.queryForList("select id, entity_type, entity_id, action, from_state, to_state, actor_name, details::text as details, created_at " +
                "from platform.audit_event where entity_type = ? order by id desc limit ?", entityType, n));
    }

    // ---------------- demo ----------------
    /** Resets this organisation's demo data in every service (each service reseeds itself). */
    @PostMapping("/demo/reset")
    @RequiresPermission("SETTINGS:CONFIGURE")
    public Map<String, Object> resetDemo() {
        if (!auth.demoMode()) throw ApiException.forbidden("Demo reset is disabled");
        UUID org = UserContextHolder.orgId();
        transactional(() -> {
            events.publish("DemoResetRequested", "Organization", org, Map.of("requestedBy", UserContextHolder.require().name()));
            return null;
        });
        return Map.of("status", "RESETTING", "message", "Demo data is being restored across all services. This takes about 10 seconds.");
    }

    private <T> T transactional(java.util.function.Supplier<T> work) {
        return tx.write(work);
    }
}
