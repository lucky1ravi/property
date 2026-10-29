package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.catalog.FeatureCatalog;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** Users, custom roles and the permission matrix. All changes are versioned and audited. */
@Service
public class AccessAdminService {
    /**
     * Scopes a company role may have; {@code PLATFORM} is reserved for the platform operator.
     */
    private static final Set<String> SCOPES = Set.of("ORG", "PROPERTY", "OWNER", "TENANT", "VENDOR");
    private final IdentityStore store;
    private final AuditService audit;
    private final EventPublisher events;
    private final ObjectMapper mapper;

    public AccessAdminService(IdentityStore store, AuditService audit, EventPublisher events, ObjectMapper mapper) {
        this.store = store;
        this.audit = audit;
        this.events = events;
        this.mapper = mapper;
    }

    // ---------------- users ----------------
    /**
     * Lists users with their roles and property access.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> users() {
        return store.users().stream().map(this::withJson).toList();
    }

    private Map<String, Object> withJson(Map<String, Object> row) {
        Map<String, Object> m = new LinkedHashMap<>(row);
        m.put("roles", json(row.get("roles")));
        m.put("property_ids", json(row.get("property_ids")));
        return m;
    }

    private Object json(Object raw) {
        try {
            return raw == null ? List.of() : mapper.readValue(raw.toString(), Object.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * Creates a user in the signed-in user's company.
     *
     * @param body email, name, title, phone, optional password (at least 10 characters), role ids, property ids
     * @return the new user
     * @throws ApiException (400) for a missing or invalid email or a short password
     */
    @Transactional
    public Map<String, Object> createUser(Map<String, Object> body) {
        UUID orgId = UserContextHolder.orgId();
        String email = req(body, "email").trim().toLowerCase();
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Enter a valid email address");
        UUID id = UUID.randomUUID();
        store.insertUser(id, orgId, email, req(body, "name"), str(body, "title"), str(body, "phone"),
                str(body, "partyType"), uuid(body.get("partyId")), str(body, "title"));
        // Nobody picks another person's password: a one-time temporary one is shown to the creator, and replaced at first sign-in.
        String temporaryPassword = AuthService.temporaryPassword();
        store.setTemporaryPassword(id, AuthService.hashPassword(temporaryPassword));
        store.setUserRoles(id, orgId, uuids(body.get("roleIds")));
        store.setUserProperties(id, orgId, uuids(body.get("propertyIds")));
        audit.record("User", id, "USER_CREATED", null, "ACTIVE", Map.of("email", email, "roles", String.valueOf(body.get("roleIds"))));
        events.publish("UserCreated", "User", id, Map.of("email", email));
        return Map.of("id", id, "temporaryPassword", temporaryPassword);
    }

    /**
     * Replaces a user's password with a new one-time temporary password they must change at next sign-in.
     *
     * @return the temporary password, shown once
     * @throws ApiException (404) for an unknown user, (400) for your own account
     */
    @Transactional
    public Map<String, Object> resetPassword(UUID id) {
        if (id.equals(UserContextHolder.require().userId())) throw ApiException.badRequest("Change your own password from the sign-in page");
        store.user(id).orElseThrow(() -> ApiException.notFound("User"));
        String temporaryPassword = AuthService.temporaryPassword();
        store.setTemporaryPassword(id, AuthService.hashPassword(temporaryPassword));
        audit.record("User", id, "USER_PASSWORD_RESET", null, null, Map.of());
        return Map.of("temporaryPassword", temporaryPassword);
    }

    /**
     * Changes the signed-in user's own name, title, phone and photo. Email is never changed here.
     *
     * @param body name, title, phone, photo (an image data URL, or null to remove it)
     */
    @Transactional
    public Map<String, Object> updateProfile(Map<String, Object> body) {
        UUID id = UserContextHolder.require().userId();
        String name = str(body, "name");
        if (name == null || name.isBlank()) throw ApiException.badRequest("Name is required");
        if (name.length() > 160) throw ApiException.badRequest("Name is too long");
        String title = str(body, "title"), phone = str(body, "phone");
        if (title != null && title.length() > 160) throw ApiException.badRequest("Job title is too long");
        if (phone != null && phone.length() > 40) throw ApiException.badRequest("Phone is too long");
        String photo = str(body, "photo");
        boolean hasPhoto = photo != null && !photo.isBlank();
        if (hasPhoto) {
            if (!photo.matches("^data:image/(jpeg|png|webp);base64,[A-Za-z0-9+/=]+$")) throw ApiException.badRequest("Photo must be a JPG, PNG or WebP image");
            if (photo.length() > 150_000) throw ApiException.badRequest("Photo is too large. Choose a smaller picture.");
        }
        store.updateProfile(id, name.trim(), title == null ? null : title.trim(), phone == null ? null : phone.trim(), hasPhoto ? photo : null);
        audit.record("User", id, "PROFILE_UPDATED", null, null, Map.of("photo", hasPhoto ? "set" : "removed"));
        return Map.of("ok", true);
    }

    /**
     * Changes a user's details and, when given, replaces their roles and properties.
     *
     * @param id   the user
     * @param body the fields to change
     */
    @Transactional
    public void updateUser(UUID id, Map<String, Object> body) {
        UUID orgId = UserContextHolder.orgId();
        store.user(id).orElseThrow(() -> ApiException.notFound("User"));
        store.updateUser(id, str(body, "name"), str(body, "title"), str(body, "phone"), str(body, "status"));
        if (body.containsKey("roleIds")) {
            if (id.equals(UserContextHolder.require().userId()) && uuids(body.get("roleIds")).isEmpty())
                throw ApiException.badRequest("You can't remove all of your own roles");
            store.setUserRoles(id, orgId, uuids(body.get("roleIds")));
        }
        if (body.containsKey("propertyIds")) store.setUserProperties(id, orgId, uuids(body.get("propertyIds")));
        audit.record("User", id, "USER_UPDATED", null, str(body, "status"), Map.of("changes", body.keySet().toString()));
    }

    /**
     * Deletes a user.
     *
     * @param id the user
     * @throws ApiException (400) when it is the signed-in user, (404) when not found
     */
    @Transactional
    public void deleteUser(UUID id) {
        if (id.equals(UserContextHolder.require().userId())) throw ApiException.badRequest("You can't delete your own account");
        if (store.deleteUser(id) == 0) throw ApiException.notFound("User");
        audit.record("User", id, "USER_DELETED", "ACTIVE", "DELETED", Map.of());
    }

    // ---------------- roles ----------------
    /**
     * Lists roles with their permissions and field rules.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> roles() {
        return store.roles().stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>(r);
            m.put("field_rules", json(r.get("field_rules")));
            m.put("permissions", store.permissions((UUID) r.get("id")));
            return m;
        }).toList();
    }

    /**
     * Creates a custom role in the signed-in user's company.
     *
     * @param body name, description, scope, colour, field rules
     * @return the new role
     */
    @Transactional
    public Map<String, Object> createRole(Map<String, Object> body) {
        UUID orgId = UserContextHolder.orgId();
        String scope = Optional.ofNullable(str(body, "scope")).orElse("ORG");
        if (!SCOPES.contains(scope)) throw ApiException.badRequest("Scope must be one of " + SCOPES);
        UUID id = UUID.randomUUID();
        UUID copyFrom = uuid(body.get("copyFromRoleId"));
        String fieldRules = "{}";
        List<Map<String, Object>> grants = new ArrayList<>();
        if (copyFrom != null) {
            Map<String, Object> src = store.role(copyFrom).orElseThrow(() -> ApiException.notFound("Role to copy"));
            fieldRules = (String) src.get("field_rules_text");
            for (Map<String, Object> p : store.permissions(copyFrom)) {
                Map<String, Object> g = new HashMap<>();
                g.put("feature", p.get("feature"));
                g.put("action", p.get("action"));
                g.put("maxAmount", p.get("max_amount"));
                grants.add(g);
            }
        }
        store.insertRole(id, orgId, null, req(body, "name"), str(body, "description"), scope, false,
                Optional.ofNullable(str(body, "color")).orElse("#3E7CB1"), fieldRules);
        store.replacePermissions(id, orgId, grants);
        audit.record("Role", id, "ROLE_CREATED", null, null, Map.of("name", req(body, "name"), "scope", scope,
                "copiedFrom", String.valueOf(copyFrom)));
        return Map.of("id", id);
    }

    /**
     * Edits a role's name, description, scope, colour or field rules.
     *
     * @param id   the role
     * @param body the fields to change
     */
    @Transactional
    public void updateRole(UUID id, Map<String, Object> body) {
        Map<String, Object> role = store.role(id).orElseThrow(() -> ApiException.notFound("Role"));
        String scope = str(body, "scope");
        if (scope != null && !SCOPES.contains(scope)) throw ApiException.badRequest("Scope must be one of " + SCOPES);
        String fieldRules = null;
        if (body.get("fieldRules") != null) {
            try {
                fieldRules = mapper.writeValueAsString(body.get("fieldRules"));
            } catch (Exception e) {
                throw ApiException.badRequest("Invalid field rules");
            }
        }
        store.updateRole(id, str(body, "name"), str(body, "description"), scope, str(body, "color"), fieldRules);
        audit.record("Role", id, "ROLE_UPDATED", null, null, Map.of("role", role.get("name"), "changes", body.keySet().toString()));
    }

    /** Replaces the role's grants (the ticks in the permission matrix). Requires maker-checker for admin roles. */
    @Transactional
    public Map<String, Object> setPermissions(UUID roleId, List<Map<String, Object>> grants) {
        UUID orgId = UserContextHolder.orgId();
        Map<String, Object> role = store.role(roleId).orElseThrow(() -> ApiException.notFound("Role"));
        UserContext me = UserContextHolder.require();
        for (Map<String, Object> g : grants) {
            String f = String.valueOf(g.get("feature")), a = String.valueOf(g.get("action"));
            if (!FeatureCatalog.valid(f, a)) throw ApiException.badRequest("Unknown permission " + f + ":" + a);
            if ("PLATFORM".equals(f) && !me.isPlatformAdmin()) throw ApiException.forbidden("Platform permissions can't be granted here");
        }
        if ("ORG_ADMIN".equals(role.get("role_key")) && grants.stream().noneMatch(g -> "ROLE".equals(g.get("feature")) && "CONFIGURE".equals(g.get("action"))))
            throw ApiException.badRequest("The Org Admin role must keep ROLE:CONFIGURE so the account can't be locked out");
        List<Map<String, Object>> before = store.permissions(roleId);
        store.replacePermissions(roleId, orgId, grants);
        store.updateRole(roleId, null, null, null, null, null);
        audit.record("Role", roleId, "PERMISSIONS_CHANGED", before.size() + " grants", grants.size() + " grants",
                Map.of("role", role.get("name")));
        events.publish("RolePermissionsChanged", "Role", roleId, Map.of("role", role.get("name"), "grants", grants.size()));
        return Map.of("roleId", roleId, "grants", grants.size(),
                "note", "Changes apply the next time affected users sign in or refresh their session.");
    }

    /**
     * Deletes a custom role.
     *
     * @param id the role
     * @throws ApiException (400) for a built-in role, (404) when not found
     */
    @Transactional
    public void deleteRole(UUID id) {
        Map<String, Object> role = store.role(id).orElseThrow(() -> ApiException.notFound("Role"));
        if (Boolean.TRUE.equals(role.get("system_role"))) throw ApiException.badRequest("Default roles can be edited but not deleted");
        store.deleteRole(id);
        audit.record("Role", id, "ROLE_DELETED", null, null, Map.of("role", role.get("name")));
    }

    // ---------------- helpers ----------------
    private static String req(Map<String, Object> b, String k) {
        String v = str(b, k);
        if (v == null || v.isBlank()) throw ApiException.badRequest(k + " is required");
        return v;
    }

    private static String str(Map<String, Object> b, String k) {
        Object v = b.get(k);
        return v == null ? null : String.valueOf(v);
    }

    private static UUID uuid(Object v) {
        return v == null || String.valueOf(v).isBlank() ? null : UUID.fromString(String.valueOf(v));
    }

    private static List<UUID> uuids(Object v) {
        if (!(v instanceof Collection<?> c)) return List.of();
        return c.stream().map(x -> UUID.fromString(String.valueOf(x))).toList();
    }
}
