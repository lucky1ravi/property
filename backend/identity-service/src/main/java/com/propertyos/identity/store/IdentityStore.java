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
    public void insertOrg(UUID id, String name, String legalName, String state, String plan) {
        jdbc.update("insert into identity.organization (id, org_id, name, legal_name, state, plan) values (?, ?, ?, ?, ?, ?)",
                id, id, name, legalName, state, plan);
    }

    public List<Map<String, Object>> orgs() {
        return jdbc.queryForList("select o.*, (select count(*) from identity.app_user u where u.org_id = o.id) as user_count " +
                "from identity.organization o order by o.created_at");
    }

    public Optional<Map<String, Object>> org(UUID id) {
        return jdbc.queryForList("select * from identity.organization where id = ?", id).stream().findFirst();
    }

    public void updateOrg(UUID id, String plan, String status) {
        jdbc.update("update identity.organization set plan = coalesce(?, plan), status = coalesce(?, status), updated_at = now() where id = ?",
                plan, status, id);
    }

    // ---------------- users ----------------
    public void insertUser(UUID id, UUID orgId, String email, String name, String title, String phone,
                           String partyType, UUID partyId, String persona) {
        jdbc.update("insert into identity.app_user (id, org_id, email, name, title, phone, party_type, party_id, persona) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?)", id, orgId, email.toLowerCase(), name, title, phone, partyType, partyId, persona);
    }

    public List<Map<String, Object>> users() {
        return jdbc.queryForList("select u.id, u.email, u.name, u.title, u.phone, u.party_type, u.party_id, u.status, u.persona, " +
                "u.mfa_enabled, u.last_login_at, u.created_at, " +
                "coalesce((select json_agg(json_build_object('id', r.id, 'name', r.name, 'color', r.color, 'scope', r.scope)) " +
                "  from identity.user_role ur join identity.role r on r.id = ur.role_id where ur.user_id = u.id), '[]')::text as roles, " +
                "coalesce((select json_agg(up.property_id) from identity.user_property up where up.user_id = u.id), '[]')::text as property_ids " +
                "from identity.app_user u order by u.name");
    }

    public Optional<Map<String, Object>> user(UUID id) {
        return jdbc.queryForList("select * from identity.app_user where id = ?", id).stream().findFirst();
    }

    public Optional<Map<String, Object>> userByEmail(String email) {
        return jdbc.queryForList("select * from identity.app_user where email = ?", email.toLowerCase()).stream().findFirst();
    }

    public void updateUser(UUID id, String name, String title, String phone, String status) {
        jdbc.update("update identity.app_user set name = coalesce(?, name), title = coalesce(?, title), phone = coalesce(?, phone), " +
                "status = coalesce(?, status), updated_at = now() where id = ?", name, title, phone, status, id);
    }

    public void touchLogin(UUID id) {
        jdbc.update("update identity.app_user set last_login_at = now() where id = ?", id);
    }

    public int deleteUser(UUID id) {
        return jdbc.update("delete from identity.app_user where id = ?", id);
    }

    public void setUserRoles(UUID userId, UUID orgId, Collection<UUID> roleIds) {
        jdbc.update("delete from identity.user_role where user_id = ?", userId);
        for (UUID r : roleIds) jdbc.update("insert into identity.user_role (user_id, role_id, org_id) values (?, ?, ?)", userId, r, orgId);
    }

    public void setUserProperties(UUID userId, UUID orgId, Collection<UUID> propertyIds) {
        jdbc.update("delete from identity.user_property where user_id = ?", userId);
        for (UUID p : propertyIds) jdbc.update("insert into identity.user_property (user_id, property_id, org_id) values (?, ?, ?)", userId, p, orgId);
    }

    public List<UUID> userPropertyIds(UUID userId) {
        return jdbc.queryForList("select property_id from identity.user_property where user_id = ?", UUID.class, userId);
    }

    public List<Map<String, Object>> userRoles(UUID userId) {
        return jdbc.queryForList("select r.* from identity.user_role ur join identity.role r on r.id = ur.role_id where ur.user_id = ?", userId);
    }

    public List<UUID> usersWithRoleKeyOrName(String roleKeyOrName) {
        return jdbc.queryForList("select distinct ur.user_id from identity.user_role ur join identity.role r on r.id = ur.role_id " +
                "where r.role_key = ? or r.name = ?", UUID.class, roleKeyOrName, roleKeyOrName);
    }

    public List<UUID> usersForParty(UUID partyId) {
        return jdbc.queryForList("select id from identity.app_user where party_id = ?", UUID.class, partyId);
    }

    public List<UUID> usersForProperty(UUID propertyId) {
        return jdbc.queryForList("select user_id from identity.user_property where property_id = ?", UUID.class, propertyId);
    }

    // ---------------- roles ----------------
    public void insertRole(UUID id, UUID orgId, String key, String name, String description, String scope,
                           boolean system, String color, String fieldRulesJson) {
        jdbc.update("insert into identity.role (id, org_id, role_key, name, description, scope, system_role, color, field_rules) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", id, orgId, key, name, description, scope, system, color, fieldRulesJson);
    }

    public List<Map<String, Object>> roles() {
        return jdbc.queryForList("select r.id, r.role_key, r.name, r.description, r.scope, r.system_role, r.color, r.version, " +
                "r.field_rules::text as field_rules, r.updated_at, " +
                "(select count(*) from identity.user_role ur where ur.role_id = r.id) as user_count, " +
                "(select count(*) from identity.role_permission p where p.role_id = r.id) as permission_count " +
                "from identity.role r order by r.system_role desc, r.name");
    }

    public Optional<Map<String, Object>> role(UUID id) {
        return jdbc.queryForList("select r.*, r.field_rules::text as field_rules_text from identity.role r where id = ?", id).stream().findFirst();
    }

    public void updateRole(UUID id, String name, String description, String scope, String color, String fieldRulesJson) {
        jdbc.update("update identity.role set name = coalesce(?, name), description = coalesce(?, description), " +
                        "scope = coalesce(?, scope), color = coalesce(?, color), field_rules = coalesce(?::jsonb, field_rules), " +
                        "version = version + 1, updated_at = now() where id = ?",
                name, description, scope, color, fieldRulesJson, id);
    }

    public int deleteRole(UUID id) {
        return jdbc.update("delete from identity.role where id = ? and system_role = false", id);
    }

    public void replacePermissions(UUID roleId, UUID orgId, List<Map<String, Object>> grants) {
        jdbc.update("delete from identity.role_permission where role_id = ?", roleId);
        for (Map<String, Object> g : grants) {
            Object amount = g.get("maxAmount");
            jdbc.update("insert into identity.role_permission (id, org_id, role_id, feature, action, max_amount) values (?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(), orgId, roleId, g.get("feature"), g.get("action"),
                    amount == null || String.valueOf(amount).isBlank() ? null : new BigDecimal(String.valueOf(amount)));
        }
    }

    public List<Map<String, Object>> permissions(UUID roleId) {
        return jdbc.queryForList("select feature, action, max_amount from identity.role_permission where role_id = ? order by feature, action", roleId);
    }

    public List<Map<String, Object>> permissionsForUser(UUID userId) {
        return jdbc.queryForList("select p.feature, p.action, p.max_amount from identity.role_permission p " +
                "join identity.user_role ur on ur.role_id = p.role_id where ur.user_id = ?", userId);
    }

    public Optional<UUID> roleIdByKey(String key) {
        return jdbc.queryForList("select id from identity.role where role_key = ?", UUID.class, key).stream().findFirst();
    }

    // ---------------- configuration ----------------
    public Optional<Map<String, Object>> config(UUID orgId) {
        return jdbc.queryForList("select config::text as config, version, updated_by, updated_at from identity.org_config where org_id = ?", orgId)
                .stream().findFirst();
    }

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

    public List<Map<String, Object>> configHistory(UUID orgId) {
        return jdbc.queryForList("select id, version, section, summary, changed_by, created_at from identity.config_history " +
                "where org_id = ? order by version desc limit 50", orgId);
    }

    public Optional<String> configAtVersion(UUID orgId, int version) {
        return jdbc.queryForList("select config::text from identity.config_history where org_id = ? and version = ?", String.class, orgId, version)
                .stream().findFirst();
    }

    // ---------------- OTP ----------------
    public void saveOtp(String email, String hash) {
        jdbc.update("insert into identity.login_otp (email, code_hash, attempts, expires_at) values (?, ?, 0, now() + interval '10 minutes') " +
                "on conflict (email) do update set code_hash = excluded.code_hash, attempts = 0, expires_at = excluded.expires_at", email, hash);
    }

    public Optional<Map<String, Object>> otp(String email) {
        return jdbc.queryForList("select * from identity.login_otp where email = ? and expires_at > now()", email).stream().findFirst();
    }

    public void bumpOtpAttempts(String email) {
        jdbc.update("update identity.login_otp set attempts = attempts + 1 where email = ?", email);
    }

    public void deleteOtp(String email) {
        jdbc.update("delete from identity.login_otp where email = ?", email);
    }

    // ---------------- demo reset ----------------
    public void deleteOrgData(UUID orgId) {
        jdbc.update("delete from identity.user_role where org_id = ?", orgId);
        jdbc.update("delete from identity.user_property where org_id = ?", orgId);
        jdbc.update("delete from identity.role_permission where org_id = ?", orgId);
        jdbc.update("delete from identity.app_user where org_id = ?", orgId);
        jdbc.update("delete from identity.role where org_id = ?", orgId);
        jdbc.update("delete from identity.config_history where org_id = ?", orgId);
        jdbc.update("delete from identity.org_config where org_id = ?", orgId);
    }

    public long countOrgs() {
        Long c = jdbc.queryForObject("select count(*) from identity.organization", Long.class);
        return c == null ? 0 : c;
    }
}
