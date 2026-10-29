package com.propertyos.identity.web;

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
    private static final Set<String> PLANS = Set.of("STARTER", "GROWTH", "ENTERPRISE");
    private final IdentityStore store;
    private final IdentitySeeder seeder;
    private final AuditService audit;
    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final com.propertyos.identity.service.ConfigService config;

    public PlatformController(IdentityStore store, IdentitySeeder seeder, AuditService audit, JdbcTemplate jdbc, Tx tx, com.propertyos.identity.service.ConfigService config) {
        this.config = config;
        this.store = store;
        this.seeder = seeder;
        this.audit = audit;
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @GetMapping("/orgs")
    public List<Map<String, Object>> orgs() {
        return tx.read(store::orgs);
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        return tx.read(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("organizations", jdbc.queryForObject("select count(*) from identity.organization where plan is not null", Long.class));
            m.put("users", jdbc.queryForObject("select count(*) from identity.app_user", Long.class));
            m.put("activeUsers7d", jdbc.queryForObject("select count(*) from identity.app_user where last_login_at > now() - interval '7 days'", Long.class));
            m.put("planMix", jdbc.queryForList("select plan, count(*) as count from identity.organization group by plan order by plan"));
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
        String name = body.get("name"), state = body.get("state"), plan = body.getOrDefault("plan", "GROWTH");
        String adminName = body.get("adminName"), adminEmail = body.get("adminEmail");
        if (name == null || state == null || adminName == null || adminEmail == null)
            throw ApiException.badRequest("name, state, adminName and adminEmail are required");
        if (!PLANS.contains(plan)) throw ApiException.badRequest("Plan must be one of " + PLANS);
        UUID orgId = UUID.randomUUID();
        UUID adminId = UUID.randomUUID();
        tx.run(() -> {
            if (store.userByEmail(adminEmail).isPresent()) throw ApiException.conflict("A user with this email already exists");
            store.insertOrg(orgId, name, body.getOrDefault("legalName", name), state.toUpperCase(), plan);
            seeder.seedOrg(orgId);
            store.insertUser(adminId, orgId, adminEmail, adminName, "Administrator", null, null, null, "Company owner / admin");
            UUID adminRole = com.propertyos.common.demo.DemoData.id("role:" + orgId + ":ORG_ADMIN");
            store.setUserRoles(adminId, orgId, List.of(adminRole));
            audit.record("Organization", orgId, "ORG_ONBOARDED", null, "ACTIVE",
                    Map.of("name", name, "plan", plan, "by", UserContextHolder.require().name()));
        });
        return Map.of("orgId", orgId, "adminUserId", adminId, "defaultRoles", DefaultRoles.TEMPLATES.size());
    }

    @GetMapping("/orgs/{id}/features")
    public List<Map<String, Object>> features(@PathVariable UUID id) {
        return tx.read(() -> config.featureMatrix(id));
    }

    @PutMapping("/orgs/{id}/features")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public List<Map<String, Object>> setFeatures(@PathVariable UUID id, @RequestBody Map<String, Object> changes) {
        return tx.write(() -> config.setEntitlements(id, changes));
    }

    @PutMapping("/orgs/{id}")
    @RequiresPermission("PLATFORM:CONFIGURE")
    public void updateOrg(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        String plan = body.get("plan");
        if (plan != null && !PLANS.contains(plan)) throw ApiException.badRequest("Plan must be one of " + PLANS);
        tx.run(() -> {
            store.updateOrg(id, plan, body.get("status"));
            audit.record("Organization", id, "ORG_UPDATED", null, body.get("status"), Map.of("plan", String.valueOf(plan)));
        });
    }
}
