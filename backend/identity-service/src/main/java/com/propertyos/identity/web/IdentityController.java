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

/**
 * Company administration: users, roles and permissions, configuration with history and rollback, and the audit trail.
 */
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
    /**
     * Lists the company's users with their roles and properties.
     */
    @GetMapping("/users")
    @RequiresPermission("USER:VIEW")
    public List<Map<String, Object>> users() {
        return access.users();
    }

    /**
     * Creates a user and assigns roles and properties.
     */
    @PostMapping("/users")
    @RequiresPermission("USER:CREATE")
    public Map<String, Object> createUser(@RequestBody Map<String, Object> body) {
        return access.createUser(body);
    }

    /**
     * Changes a user's details, status, roles or properties.
     */
    @PutMapping("/users/{id}")
    @RequiresPermission("USER:EDIT")
    public void updateUser(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        access.updateUser(id, body);
    }

    /**
     * Deletes a user. A person cannot delete their own account.
     */
    @DeleteMapping("/users/{id}")
    @RequiresPermission("USER:DELETE")
    public void deleteUser(@PathVariable UUID id) {
        access.deleteUser(id);
    }

    // ---------------- roles and permission matrix ----------------
    /**
     * Returns the catalogue of features, actions, scopes and field rules used by the role editor.
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        return Map.of("features", FeatureCatalog.FEATURES, "actions", FeatureCatalog.ACTIONS,
                "scopes", FeatureCatalog.SCOPES, "fields", FeatureCatalog.FIELDS);
    }

    /**
     * Lists the company's roles with their permissions.
     */
    @GetMapping("/roles")
    @RequiresPermission({"ROLE:VIEW", "USER:VIEW"})
    public List<Map<String, Object>> roles() {
        return access.roles();
    }

    /**
     * Creates a custom role.
     */
    @PostMapping("/roles")
    @RequiresPermission("ROLE:CONFIGURE")
    public Map<String, Object> createRole(@RequestBody Map<String, Object> body) {
        return access.createRole(body);
    }

    /**
     * Edits a role's name, description, scope, colour or field rules.
     */
    @PutMapping("/roles/{id}")
    @RequiresPermission("ROLE:CONFIGURE")
    public void updateRole(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        access.updateRole(id, body);
    }

    /**
     * Replaces a role's permissions.
     */
    @PutMapping("/roles/{id}/permissions")
    @RequiresPermission("ROLE:CONFIGURE")
    public Map<String, Object> setPermissions(@PathVariable UUID id, @RequestBody List<Map<String, Object>> grants) {
        return access.setPermissions(id, grants);
    }

    /**
     * Deletes a custom role. Built-in roles can be edited but not deleted.
     */
    @DeleteMapping("/roles/{id}")
    @RequiresPermission("ROLE:CONFIGURE")
    public void deleteRole(@PathVariable UUID id) {
        access.deleteRole(id);
    }

    // ---------------- configuration ----------------
    /**
     * Returns the effective configuration: the company's changes merged over the defaults.
     */
    @GetMapping("/config")
    public ObjectNode config() {
        return config.effective(UserContextHolder.orgId());
    }

    /**
     * Returns only what the company has changed from the defaults.
     */
    @GetMapping("/config/overrides")
    @RequiresPermission("SETTINGS:VIEW")
    public ObjectNode overrides() {
        return config.overrides(UserContextHolder.orgId());
    }

    /**
     * Replaces one configuration section and records it in the history.
     */
    @PutMapping("/config/{section}")
    @RequiresPermission("SETTINGS:CONFIGURE")
    public ObjectNode updateConfig(@PathVariable String section, @RequestBody JsonNode body,
                                   @RequestParam(required = false) String summary) {
        return config.updateSection(section, body, summary);
    }

    /**
     * Lists recent configuration versions.
     */
    @GetMapping("/config/history")
    @RequiresPermission("SETTINGS:VIEW")
    public List<Map<String, Object>> history() {
        return config.history();
    }

    /**
     * Restores the configuration as it was at an earlier version.
     */
    @PostMapping("/config/rollback/{version}")
    @RequiresPermission("SETTINGS:CONFIGURE")
    public ObjectNode rollback(@PathVariable int version) {
        return config.rollback(version);
    }

    // ---------------- audit ----------------
    /**
     * Returns the audit trail of one record, oldest first.
     */
    @GetMapping("/audit/{entityId}")
    public List<Map<String, Object>> timeline(@PathVariable UUID entityId) {
        return transactional(() -> audit.timeline(entityId));
    }

    /**
     * Returns the latest audit entries, optionally for one kind of record. The limit is capped at 500.
     */
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
