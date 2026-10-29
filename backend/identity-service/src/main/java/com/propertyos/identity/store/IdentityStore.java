package com.propertyos.identity.store;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.*;

/**
 * Data access for identity tables. Always called inside a transaction, so row-level
 * security settings are in place and each query only sees the caller's organisation.
 */
@Repository
public class IdentityStore {
    private final JdbcTemplate jdbc;

    public IdentityStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------- organisations ----------------
    /**
     * Inserts a company.
     */
    public void insertOrg(UUID id, String name, String legalName, String state, String plan) {
        jdbc.update("insert into identity.organization (id, org_id, name, legal_name, state, plan) values (?, ?, ?, ?, ?, ?)",
                id, id, name, legalName, state, plan);
    }

    /**
     * Lists companies with their user counts.
     */
    public List<Map<String, Object>> orgs() {
        return jdbc.queryForList("select o.*, (select count(*) from identity.app_user u where u.org_id = o.id) as user_count " +
                "from identity.organization o order by o.created_at");
    }

    /**
     * Finds a company.
     */
    public Optional<Map<String, Object>> org(UUID id) {
        return jdbc.queryForList("select * from identity.organization where id = ?", id).stream().findFirst();
    }

    /**
     * Stores a company's feature overrides (JSON).
     */
    public void setEntitlements(UUID id, String json) {
        jdbc.update("update identity.organization set entitlements = ?, updated_at = now() where id = ?", json, id);
    }

    /**
     * Changes a company's plan and/or status; null leaves a value unchanged.
     */
    public void updateOrg(UUID id, String plan, String status) {
        jdbc.update("update identity.organization set plan = coalesce(?, plan), status = coalesce(?, status), updated_at = now() where id = ?",
                plan, status, id);
    }

    /** Records a change of status: the reason (kept only while suspended), who made it and when. */
    public void setOrgStatus(UUID id, String status, String reason, String by) {
        jdbc.update("update identity.organization set status = ?, status_reason = ?, status_changed_by = ?, status_changed_at = now(), updated_at = now() where id = ?",
                status, "SUSPENDED".equals(status) ? reason : null, by, id);
    }

    /** Ids of companies that are not active. */
    public List<UUID> suspendedOrgIds() {
        return jdbc.queryForList("select id from identity.organization where status <> 'ACTIVE'", UUID.class);
    }

    // ---------------- users ----------------
    /**
     * Inserts a user.
     */
    public void insertUser(UUID id, UUID orgId, String email, String name, String title, String phone,
                           String partyType, UUID partyId, String persona) {
        jdbc.update("insert into identity.app_user (id, org_id, email, name, title, phone, party_type, party_id, persona) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, orgId, email.toLowerCase(), name, title, phone, partyType, partyId, persona);
    }

    /**
     * Lists users with role names and property counts.
     */
    public List<Map<String, Object>> users() {
        return jdbc.queryForList("select u.id, u.email, u.name, u.title, u.phone, u.party_type, u.party_id, u.status, u.persona, " +
                "u.mfa_enabled, u.last_login_at, u.created_at, " +
                "coalesce((select json_agg(json_build_object('id', r.id, 'name', r.name, 'color', r.color, 'scope', r.scope)) " +
                "  from identity.user_role ur join identity.role r on r.id = ur.role_id where ur.user_id = u.id), '[]')::text as roles, " +
                "coalesce((select json_agg(up.property_id) from identity.user_property up where up.user_id = u.id), '[]')::text as property_ids " +
                "from identity.app_user u order by u.name");
    }

    /**
     * Finds a user by id.
     */
    public Optional<Map<String, Object>> user(UUID id) {
        return jdbc.queryForList("select * from identity.app_user where id = ?", id).stream().findFirst();
    }

    /**
     * Finds a user by email address (case-insensitive).
     */
    public Optional<Map<String, Object>> userByEmail(String email) {
        return jdbc.queryForList("select * from identity.app_user where email = ?", email.toLowerCase()).stream().findFirst();
    }

    /**
     * Changes a user's details; null leaves a value unchanged.
     */
    public void updateUser(UUID id, String name, String title, String phone, String status) {
        jdbc.update("update identity.app_user set name = coalesce(?, name), title = coalesce(?, title), phone = coalesce(?, phone), " +
                "status = coalesce(?, status), updated_at = now() where id = ?", name, title, phone, status, id);
    }

    /** Sets a person's own name, title, phone and photo (null photo removes it). Email is never touched. */
    public void updateProfile(UUID id, String name, String title, String phone, String photo) {
        jdbc.update("update identity.app_user set name = ?, title = ?, phone = ?, photo = ?, updated_at = now() where id = ?", name, title, phone, photo, id);
    }

    /**
     * Stores a password hash.
     */
    public void setPassword(UUID id, String hash) {
        jdbc.update("update identity.app_user set password_hash = ?, must_change_password = false, updated_at = now() where id = ?", hash, id);
    }

    /**
     * Stores a temporary password hash. The user must choose their own password before signing in.
     */
    public void setTemporaryPassword(UUID id, String hash) {
        jdbc.update("update identity.app_user set password_hash = ?, must_change_password = true, updated_at = now() where id = ?", hash, id);
    }

    /**
     * Records the time of the user's latest sign-in.
     */
    public void touchLogin(UUID id) {
        jdbc.update("update identity.app_user set last_login_at = now() where id = ?", id);
    }

    /**
     * Deletes a user; returns the rows removed.
     */
    public int deleteUser(UUID id) {
        return jdbc.update("delete from identity.app_user where id = ?", id);
    }

    /**
     * Replaces a user's roles, accepting only roles of the same company.
     */
    public void setUserRoles(UUID userId, UUID orgId, Collection<UUID> roleIds) {
        for (UUID r : roleIds) {
            Integer own = jdbc.queryForObject("select count(*) from identity.role where id = ? and org_id = ?", Integer.class, r, orgId);
            if (own == null || own == 0) throw com.propertyos.common.web.ApiException.badRequest("One of the selected roles doesn't exist in this company.");
        }
        jdbc.update("delete from identity.user_role where user_id = ?", userId);
        for (UUID r : roleIds) jdbc.update("insert into identity.user_role (user_id, role_id, org_id) values (?, ?, ?)", userId, r, orgId);
    }

    /**
     * Replaces the properties a user may see.
     */
    public void setUserProperties(UUID userId, UUID orgId, Collection<UUID> propertyIds) {
        jdbc.update("delete from identity.user_property where user_id = ?", userId);
        for (UUID p : propertyIds) jdbc.update("insert into identity.user_property (user_id, property_id, org_id) values (?, ?, ?)", userId, p, orgId);
    }

    /**
     * Lists the property ids a user may see.
     */
    public List<UUID> userPropertyIds(UUID userId) {
        return jdbc.queryForList("select property_id from identity.user_property where user_id = ?", UUID.class, userId);
    }

    /**
     * Lists the roles a user holds.
     */
    public List<Map<String, Object>> userRoles(UUID userId) {
        return jdbc.queryForList("select r.* from identity.user_role ur join identity.role r on r.id = ur.role_id and r.org_id = ur.org_id where ur.user_id = ?", userId);
    }

    /**
     * Lists users holding a role, matched by role key or name.
     */
    public List<UUID> usersWithRoleKeyOrName(String roleKeyOrName) {
        return jdbc.queryForList("select distinct ur.user_id from identity.user_role ur join identity.role r on r.id = ur.role_id " +
                "where r.role_key = ? or r.name = ?", UUID.class, roleKeyOrName, roleKeyOrName);
    }

    /**
     * Lists portal users linked to a resident, owner or vendor.
     */
    public List<UUID> usersForParty(UUID partyId) {
        return jdbc.queryForList("select id from identity.app_user where party_id = ?", UUID.class, partyId);
    }

    /**
     * Lists users assigned to a property.
     */
    public List<UUID> usersForProperty(UUID propertyId) {
        return jdbc.queryForList("select user_id from identity.user_property where property_id = ?", UUID.class, propertyId);
    }

    // ---------------- roles ----------------
    /**
     * Inserts a role.
     */
    public void insertRole(UUID id, UUID orgId, String key, String name, String description, String scope,
                           boolean system, String color, String fieldRulesJson) {
        jdbc.update("insert into identity.role (id, org_id, role_key, name, description, scope, system_role, color, field_rules) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", id, orgId, key, name, description, scope, system, color, fieldRulesJson);
    }

    /**
     * Lists roles with user counts.
     */
    public List<Map<String, Object>> roles() {
        return jdbc.queryForList("select r.id, r.role_key, r.name, r.description, r.scope, r.system_role, r.color, r.version, " +
                "r.field_rules::text as field_rules, r.updated_at, " +
                "(select count(*) from identity.user_role ur where ur.role_id = r.id) as user_count, " +
                "(select count(*) from identity.role_permission p where p.role_id = r.id) as permission_count " +
                "from identity.role r order by r.system_role desc, r.name");
    }

    /**
     * Finds a role by id.
     */
    public Optional<Map<String, Object>> role(UUID id) {
        return jdbc.queryForList("select r.*, r.field_rules::text as field_rules_text from identity.role r where id = ?", id).stream().findFirst();
    }

    /**
     * Changes a role; null leaves a value unchanged.
     */
    public void updateRole(UUID id, String name, String description, String scope, String color, String fieldRulesJson) {
        jdbc.update("update identity.role set name = coalesce(?, name), description = coalesce(?, description), " +
                        "scope = coalesce(?, scope), color = coalesce(?, color), field_rules = coalesce(?::jsonb, field_rules), " +
                        "version = version + 1, updated_at = now() where id = ?",
                name, description, scope, color, fieldRulesJson, id);
    }

    /**
     * Deletes a custom role; built-in roles are never deleted. Returns the rows removed.
     */
    public int deleteRole(UUID id) {
        return jdbc.update("delete from identity.role where id = ? and system_role = false", id);
    }

    /**
     * Replaces all permissions of a role.
     */
    public void replacePermissions(UUID roleId, UUID orgId, List<Map<String, Object>> grants) {
        jdbc.update("delete from identity.role_permission where role_id = ?", roleId);
        for (Map<String, Object> g : grants) {
            Object amount = g.get("maxAmount");
            jdbc.update("insert into identity.role_permission (id, org_id, role_id, feature, action, max_amount) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), orgId, roleId, g.get("feature"), g.get("action"),
                    amount == null || String.valueOf(amount).isBlank() ? null : new BigDecimal(String.valueOf(amount)));
        }
    }

    /**
     * Lists a role's permissions.
     */
    public List<Map<String, Object>> permissions(UUID roleId) {
        return jdbc.queryForList("select feature, action, max_amount from identity.role_permission where role_id = ? order by feature, action", roleId);
    }

    /**
     * Lists every permission a user gets from all their roles.
     */
    public List<Map<String, Object>> permissionsForUser(UUID userId) {
        return jdbc.queryForList("select p.feature, p.action, p.max_amount from identity.role_permission p " +
                "join identity.user_role ur on ur.role_id = p.role_id join identity.role r on r.id = ur.role_id and r.org_id = ur.org_id where ur.user_id = ?", userId);
    }

    /**
     * Finds a role id by its built-in key.
     */
    public Optional<UUID> roleIdByKey(String key) {
        return jdbc.queryForList("select id from identity.role where role_key = ?", UUID.class, key).stream().findFirst();
    }

    // ---------------- configuration ----------------
    /**
     * Finds a company's stored configuration and version.
     */
    public Optional<Map<String, Object>> config(UUID orgId) {
        return jdbc.queryForList("select config::text as config, version, updated_by, updated_at from identity.org_config where org_id = ?", orgId)
                .stream().findFirst();
    }

    /**
     * Saves a company's configuration as the next version and writes the history row.
     *
     * @return the new version number
     */
    public int saveConfig(UUID orgId, String json, String section, String summary, String by) {
        Integer version = jdbc.queryForObject(
                "insert into identity.org_config (org_id, config, version, updated_by, updated_at) values (?, ?::jsonb, 1, ?, now()) " +
                        "on conflict (org_id) do update set config = excluded.config, version = identity.org_config.version + 1, " +
                        "updated_by = excluded.updated_by, updated_at = now() returning version",
                Integer.class, orgId, json, by);
        jdbc.update("insert into identity.config_history (org_id, version, config, section, summary, changed_by) values (?, ?, ?::jsonb, ?, ?, ?)",
                orgId, version, json, section, summary, by);
        return version == null ? 1 : version;
    }

    /**
     * Lists the 50 latest configuration versions.
     */
    public List<Map<String, Object>> configHistory(UUID orgId) {
        return jdbc.queryForList("select id, version, section, summary, changed_by, created_at from identity.config_history " +
                "where org_id = ? order by version desc limit 50", orgId);
    }

    /**
     * Returns the configuration JSON saved at a version.
     */
    public Optional<String> configAtVersion(UUID orgId, int version) {
        return jdbc.queryForList("select config::text from identity.config_history where org_id = ? and version = ?", String.class, orgId, version)
                .stream().findFirst();
    }

    // ---------------- OTP ----------------
    /**
     * Stores the hash of a one-time code that expires in 10 minutes, replacing any earlier code.
     */
    public void saveOtp(String email, String hash) {
        jdbc.update("insert into identity.login_otp (email, code_hash, attempts, expires_at) values (?, ?, 0, now() + interval '10 minutes') " +
                "on conflict (email) do update set code_hash = excluded.code_hash, attempts = 0, expires_at = excluded.expires_at", email, hash);
    }

    /**
     * Finds an unexpired one-time code.
     */
    public Optional<Map<String, Object>> otp(String email) {
        return jdbc.queryForList("select * from identity.login_otp where email = ? and expires_at > now()", email).stream().findFirst();
    }

    /**
     * Counts a failed attempt against a code.
     */
    public void bumpOtpAttempts(String email) {
        jdbc.update("update identity.login_otp set attempts = attempts + 1 where email = ?", email);
    }

    /**
     * Removes a one-time code.
     */
    public void deleteOtp(String email) {
        jdbc.update("delete from identity.login_otp where email = ?", email);
    }

    // ---------------- demo reset ----------------
    /**
     * Deletes a company's users, roles and permissions (used by demo reset).
     */
    public void deleteOrgData(UUID orgId) {
        jdbc.update("delete from identity.user_role where org_id = ?", orgId);
        jdbc.update("delete from identity.user_property where org_id = ?", orgId);
        jdbc.update("delete from identity.role_permission where org_id = ?", orgId);
        jdbc.update("delete from identity.app_user where org_id = ?", orgId);
        jdbc.update("delete from identity.role where org_id = ?", orgId);
        jdbc.update("delete from identity.config_history where org_id = ?", orgId);
        jdbc.update("delete from identity.org_config where org_id = ?", orgId);
    }

    // ---------------- catalog sync ----------------
    /**
     * Tells whether a new catalogue feature has already been added to a company's roles.
     */
    public boolean featureSynced(UUID orgId, String feature) {
        Long c = jdbc.queryForObject("select count(*) from identity.catalog_sync where org_id = ? and feature = ?", Long.class, orgId, feature);
        return c != null && c > 0;
    }

    /**
     * Remembers that a catalogue feature was added to a company's roles.
     */
    public void markFeatureSynced(UUID orgId, String feature) {
        jdbc.update("insert into identity.catalog_sync (org_id, feature) values (?, ?) on conflict do nothing", orgId, feature);
    }

    /**
     * Lists a company's built-in roles.
     */
    public List<Map<String, Object>> systemRoles(UUID orgId) {
        return jdbc.queryForList("select id, role_key from identity.role where org_id = ? and role_key is not null", orgId);
    }

    /**
     * Adds a permission to a role unless it already has it.
     */
    public void addGrantIfMissing(UUID roleId, UUID orgId, String feature, String action, BigDecimal max) {
        jdbc.update("insert into identity.role_permission (id, org_id, role_id, feature, action, max_amount) values (?, ?, ?, ?, ?, ?) on conflict (role_id, feature, action) do nothing",
                UUID.randomUUID(), orgId, roleId, feature, action, max);
    }

    /** Gives every role that can view properties the new Owners view permission, once, so nothing disappears for existing roles. */
    public void grantOwnerViewToPropertyViewers(UUID orgId) {
        jdbc.update("insert into identity.role_permission (id, org_id, role_id, feature, action) " +
                "select gen_random_uuid(), org_id, role_id, 'OWNER', 'VIEW' from identity.role_permission " +
                "where org_id = ? and feature = 'PROPERTY' and action = 'VIEW' on conflict (role_id, feature, action) do nothing", orgId);
    }

    /** Adds a field rule to a role only when the role has no rule for that field yet (an admin's choice is never overwritten). */
    public void addFieldRuleIfMissing(UUID roleId, String field, String access) {
        jdbc.update("update identity.role set field_rules = coalesce(field_rules, '{}'::jsonb) || jsonb_build_object(?::text, ?::text), updated_at = now() " +
                "where id = ? and not jsonb_exists(coalesce(field_rules, '{}'::jsonb), ?)", field, access, roleId, field);
    }

    /**
     * Counts companies.
     */
    public long countOrgs() {
        Long c = jdbc.queryForObject("select count(*) from identity.organization", Long.class);
        return c == null ? 0 : c;
    }

    /** Product-level defaults the operator has set: section name to JSON text. */
    public Map<String, String> productDefaults() {
        Map<String, String> m = new java.util.LinkedHashMap<>();
        jdbc.queryForList("select section, value from identity.product_default order by section")
                .forEach(r -> m.put((String) r.get("section"), (String) r.get("value")));
        return m;
    }

    /** Sets (or replaces) one section of the product defaults. */
    public void saveProductDefault(String section, String json, String by) {
        jdbc.update("insert into identity.product_default (section, value, updated_by, updated_at) values (?, ?, ?, now()) "
                + "on conflict (section) do update set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()", section, json, by);
    }

    /** Removes the operator's change to a section, so it falls back to the built-in default. */
    public void deleteProductDefault(String section) {
        jdbc.update("delete from identity.product_default where section = ?", section);
    }

    /** Ids of every company, for refreshing their configuration. */
    public List<UUID> orgIds() {
        return jdbc.queryForList("select id from identity.organization", UUID.class);
    }
}
