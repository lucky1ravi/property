package com.propertyos.identity.web;

import com.propertyos.identity.service.AuthService;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Tx;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.catalog.DefaultRoles;
import com.propertyos.identity.seed.IdentitySeeder;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/** PropertyOS operator console: organisations, plans and platform-wide usage. */
@RestController
@RequestMapping("/api/identity/platform")
@RequiresPermission("PLATFORM:VIEW")
public class PlatformController {
    private final IdentityStore store;
    private final IdentitySeeder seeder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final com.propertyos.identity.service.ConfigService config;
    private final com.propertyos.identity.service.PlanService plans;
    private final com.propertyos.common.events.EventPublisher events;
    private final com.propertyos.common.security.SuspendedOrgs suspended;

    public PlatformController(IdentityStore store, IdentitySeeder seeder, AuditService audit, JdbcTemplate jdbc, Tx tx, com.propertyos.identity.service.ConfigService config, com.propertyos.identity.service.PlanService plans, com.propertyos.common.events.EventPublisher events, com.propertyos.common.security.SuspendedOrgs suspended) {
        this.events = events;
        this.suspended = suspended;
        this.config = config;
        this.plans = plans;
        this.store = store;
        this.seeder = seeder;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    /**
     * Platform administrators: lists customer companies.
     */
    @GetMapping("/orgs")
    public List<Map<String, Object>> orgs(@RequestParam(required = false) List<String> status) {
        List<String> selected = status == null || status.isEmpty() ? List.of("ACTIVE") : status.stream()
                .map(String::toUpperCase).distinct().toList();
        if (selected.stream().anyMatch(s -> !List.of("ACTIVE", "SUSPENDED", "DELETED").contains(s)))
            throw ApiException.badRequest("Status must be ACTIVE, SUSPENDED or DELETED.");
        return tx.read(() -> store.orgs(selected));
    }

    /**
     * Platform administrators: console figures (companies, users, users active in the last 7 days, plan mix, daily sign-ins and recent configuration changes).
     */
    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return tx.read(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizations", jdbc.queryForObject("select count(*) from identity.organization where status = 'ACTIVE'", Long.class));
            m.put("users", jdbc.queryForObject("select count(*) from identity.app_user u join identity.organization o on o.id = u.org_id where o.status = 'ACTIVE'", Long.class));
            m.put("activeUsers7d", jdbc.queryForObject("select count(*) from identity.app_user u join identity.organization o on o.id = u.org_id where o.status = 'ACTIVE' and u.last_login_at > now() - interval '7 days'", Long.class));
            m.put("planMix", jdbc.queryForList("select plan, count(*) as count from identity.organization where status = 'ACTIVE' group by plan order by plan"));
            m.put("signIns", jdbc.queryForList("select date_trunc('day', created_at)::date as day, count(*) as count from platform.audit_event " +
                    "where action = 'SIGNED_IN' and created_at > now() - interval '14 days' group by 1 order by 1"));
            m.put("configChanges30d", jdbc.queryForObject("select count(*) from platform.audit_event where action like 'CONFIG_%' and created_at > now() - interval '30 days'", Long.class));
            return m;
        });
    }

    /** Onboards a new management company with default roles, its first admin and default configuration. */
    @PostMapping("/orgs")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> onboard(@RequestBody Map<String, String> body) {
        String name = body.get("name"), state = body.get("state"), plan = body.get("plan");
        String adminName = body.get("adminName"), adminEmail = body.get("adminEmail");
        if (name == null || state == null || adminName == null || adminEmail == null)
            throw ApiException.badRequest("name, state, adminName and adminEmail are required");
        if (plan == null || plan.isBlank()) plan = tx.read(plans::defaultPlan);
        String chosenPlan = plan;
        if (!tx.read(() -> plans.exists(chosenPlan))) throw ApiException.badRequest("That plan doesn't exist. Pick one from the plans list.");
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        String temporaryPassword = AuthService.temporaryPassword();
        tx.run(() -> {
            if (store.userByEmail(adminEmail).isPresent()) throw ApiException.conflict("A user with this email already exists");
            store.insertOrg(orgId, name, body.getOrDefault("legalName", name), state.toUpperCase(), chosenPlan);
            seeder.seedOrg(orgId);
            store.insertUser(adminId, orgId, adminEmail, adminName, "Administrator", null, null, null, "Company owner / admin");
            UUID adminRole = com.propertyos.common.demo.DemoData.id("role:" + orgId + ":ORG_ADMIN");
            store.setUserRoles(adminId, orgId, List.of(adminRole));
            store.setTemporaryPassword(adminId, AuthService.hashPassword(temporaryPassword));
            audit.record("Organization", orgId, "ORG_ONBOARDED", null, "ACTIVE",
                    Map.of("name", name, "plan", chosenPlan, "by", UserContextHolder.require().name()));
            // Other services give the new company what it starts with (for example the automation rule library).
            events.publish(orgId, "OrganizationOnboarded", "Organization", orgId, Map.of("name", name));
        });
        // Shown once to the platform operator, who passes it to the new administrator. Only its hash is stored.
        return Map.of("orgId", orgId, "adminUserId", adminId, "defaultRoles", DefaultRoles.TEMPLATES.size(), "temporaryPassword", temporaryPassword);
    }

    /**
     * Platform administrators: one company's profile. Covers the plan and status, the primary contact (its earliest
     * organisation administrator), all administrators, headcount and sign-in activity, and the features switched on.
     * Only account information is returned, never the company's business data.
     */
    @GetMapping("/orgs/{id}")
    public Map<String, Object> orgDetails(@PathVariable UUID id) {
        return tx.read(() -> {
            Map<String, Object> org = store.org(id).orElseThrow(() -> ApiException.notFound("Company not found."));
            Map<String, Object> m = new LinkedHashMap<>(org);
            List<Map<String, Object>> admins = jdbc.queryForList(
                    "select u.id, u.name, u.email, u.title, u.phone, u.status, u.mfa_enabled, u.last_login_at, u.created_at, u.must_change_password " +
                    "from identity.app_user u join identity.user_role ur on ur.user_id = u.id join identity.role r on r.id = ur.role_id " +
                    "where u.org_id = ? and r.role_key = 'ORG_ADMIN' order by u.created_at", id);
            m.put("administrators", admins);
            m.put("primaryContact", admins.isEmpty() ? null : admins.get(0));
            m.put("userStats", jdbc.queryForMap(
                    "select count(*) as total, count(*) filter (where status = 'ACTIVE') as active, " +
                    "count(*) filter (where last_login_at > now() - interval '7 days') as active_7d, " +
                    "count(*) filter (where must_change_password) as awaiting_first_sign_in, max(last_login_at) as last_sign_in " +
                    "from identity.app_user where org_id = ?", id));
            m.put("roleCount", jdbc.queryForObject("select count(*) from identity.role where org_id = ?", Long.class, id));
            m.put("enabledFeatures", config.featureMatrix(id).stream().filter(f -> Boolean.TRUE.equals(f.get("enabled"))).map(f -> f.get("feature")).toList());
            return m;
        });
    }

    /**
     * Platform administrators: a company's feature switches with plan defaults and overrides.
     */
    @GetMapping("/orgs/{id}/features")
    public List<Map<String, Object>> features(@PathVariable UUID id) {
        return tx.read(() -> config.featureMatrix(id));
    }

    /**
     * Platform administrators: turns individual features on or off for a company.
     */
    @PutMapping("/orgs/{id}/features")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> setFeatures(@PathVariable UUID id, @RequestBody Map<String, Object> changes) {
        return tx.write(() -> config.setEntitlements(id, changes));
    }

    /** Platform administrators: changes a company's profile, plan or status. */
    @PutMapping("/orgs/{id}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public void updateOrg(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String name = body.get("name"), legalName = body.get("legalName"), state = body.get("state");
        String plan = body.get("plan"), status = body.get("status"), reason = body.get("reason");
        if (name != null && name.isBlank()) throw ApiException.badRequest("Company name can't be blank.");
        if (state != null && !state.trim().matches("[A-Za-z]{2}")) throw ApiException.badRequest("State must be a two-letter code.");
        String normalizedState = state == null ? null : state.trim().toUpperCase(Locale.ROOT);
        if (plan != null && !tx.read(() -> plans.exists(plan))) throw ApiException.badRequest("That plan doesn't exist.");
        if (status != null && !List.of("ACTIVE", "SUSPENDED").contains(status)) throw ApiException.badRequest("Status must be ACTIVE or SUSPENDED.");
        if (id.equals(com.propertyos.common.demo.DemoData.PLATFORM.id()) && status != null && !"ACTIVE".equals(status))
            throw ApiException.badRequest("The platform's own account can't be suspended.");
        if ("SUSPENDED".equals(status) && (reason == null || reason.isBlank())) throw ApiException.badRequest("Say why the company is being suspended, for the audit trail.");
        tx.run(() -> {
            Map<String, Object> current = store.org(id).orElseThrow(() -> ApiException.notFound("Company not found."));
            if ("DELETED".equals(current.get("status"))) throw ApiException.conflict("A deleted company can't be changed.");
            String by = UserContextHolder.require().name();
            Map<String, Object> changed = new LinkedHashMap<>();
            if (name != null) changed.put("name", name.trim());
            if (legalName != null) changed.put("legalName", legalName.trim());
            if (normalizedState != null) changed.put("state", normalizedState);
            if (plan != null) changed.put("plan", plan);
            if (!changed.isEmpty()) store.updateOrg(id, name == null ? null : name.trim(), legalName == null ? null : legalName.trim(), normalizedState, plan);
            if (status != null) {
                store.setOrgStatus(id, status, reason == null ? null : reason.trim(), by);
                audit.record("Organization", id, "SUSPENDED".equals(status) ? "ORG_SUSPENDED" : "ORG_RESTORED", null, status,
                        Map.of("reason", reason == null ? "" : reason.trim(), "by", by));
            }
            if (!changed.isEmpty()) audit.record("Organization", id, "ORG_UPDATED", null, status, changed);
        });
        suspended.replace(tx.read(store::suspendedOrgIds)); // takes effect here at once; other services pick it up within seconds
    }

    /** Soft-deletes a company while retaining its records and audit history. */
    @DeleteMapping("/orgs/{id}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public void deleteOrg(@PathVariable UUID id) {
        if (id.equals(com.propertyos.common.demo.DemoData.PLATFORM.id()))
            throw ApiException.badRequest("The platform's own account can't be deleted.");
        tx.run(() -> {
            Map<String, Object> org = store.org(id).orElseThrow(() -> ApiException.notFound("Company not found."));
            if ("DELETED".equals(org.get("status"))) throw ApiException.conflict("This company is already deleted.");
            String by = UserContextHolder.require().name();
            store.softDeleteOrg(id, by);
            audit.record("Organization", id, "ORG_DELETED", String.valueOf(org.get("status")), "DELETED", Map.of("by", by));
        });
        suspended.replace(tx.read(store::suspendedOrgIds)); // deleted companies are blocked straight away
    }
}
