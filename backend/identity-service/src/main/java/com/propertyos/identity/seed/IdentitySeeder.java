package com.propertyos.identity.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.identity.catalog.DefaultRoles;
import com.propertyos.identity.store.IdentityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Seeds organisations, default roles, demo users and configuration; handles demo resets. */
@Component
public class IdentitySeeder implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(IdentitySeeder.class);
    private final IdentityStore store;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final boolean demo;

    public IdentitySeeder(IdentityStore store, ObjectMapper mapper, PlatformTransactionManager tm,
                          @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.store = store;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) return;
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                if (store.countOrgs() > 0) return;
                for (DemoData.Org o : DemoData.orgs()) store.insertOrg(o.id(), o.name(), o.legalName(), o.state(), o.plan());
                for (DemoData.Org o : DemoData.orgs()) seedOrg(o.id());
                log.info("Seeded identity demo data");
            });
            return null;
        });
    }

    /** Roles, users and configuration for one organisation (organisation row must exist). */
    public void seedOrg(UUID orgId) {
        Map<String, UUID> roleIds = new HashMap<>();
        List<DefaultRoles.RoleTemplate> templates = orgId.equals(DemoData.PLATFORM.id())
                ? List.of(DefaultRoles.PLATFORM_ADMIN) : DefaultRoles.TEMPLATES;
        for (DefaultRoles.RoleTemplate t : templates) {
            UUID rid = DemoData.id("role:" + orgId + ":" + t.key());
            try {
                store.insertRole(rid, orgId, t.key(), t.name(), t.description(), t.scope(), true, t.color(),
                        mapper.writeValueAsString(t.fieldRules()));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            List<Map<String, Object>> grants = new ArrayList<>();
            for (DefaultRoles.Grant g : t.grants()) {
                Map<String, Object> m = new HashMap<>();
                m.put("feature", g.feature());
                m.put("action", g.action());
                m.put("maxAmount", g.maxAmount());
                grants.add(m);
            }
            store.replacePermissions(rid, orgId, grants);
            roleIds.put(t.key(), rid);
        }
        for (DemoData.User u : DemoData.users()) {
            if (!u.orgId().equals(orgId)) continue;
            String partyType = switch (u.roleKey()) {
                case "OWNER" -> "OWNER";
                case "TENANT" -> "TENANT";
                case "VENDOR" -> "VENDOR";
                default -> null;
            };
            store.insertUser(u.id(), orgId, u.email(), u.name(), u.title(), null, partyType, u.partyId(), u.persona());
            store.setUserRoles(u.id(), orgId, List.of(roleIds.get(u.roleKey())));
            if (!u.propertyIds().isEmpty()) store.setUserProperties(u.id(), orgId, u.propertyIds());
        }
        ObjectNode overrides = mapper.createObjectNode();
        if (orgId.equals(DemoData.HARBOR.id())) {
            ObjectNode b = overrides.putObject("branding");
            b.put("companyName", "Harborview Residential");
            b.put("primaryColor", "#123C4A");
            b.put("accentColor", "#E07A5F");
        }
        store.saveConfig(orgId, overrides.toString(), "*", "Organisation onboarded", "System");
    }

    // Demo reset: wipe and reseed this organisation's identity data.
    @Override
    public String subscriber() {
        return "identity.demo-reset";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    @Override
    public void handle(DomainEvent event) {
        UUID orgId = event.orgId();
        UserContextHolder.runAsSystem(null, () -> {
            store.deleteOrgData(orgId);
            seedOrg(orgId);
            return null;
        });
        log.info("Identity data reset for org {}", orgId);
    }
}
