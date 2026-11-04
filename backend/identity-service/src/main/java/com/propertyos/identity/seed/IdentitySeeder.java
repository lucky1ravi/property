package com.propertyos.identity.seed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.demo.PitchData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.identity.catalog.DefaultRoles;
import com.propertyos.identity.store.IdentityStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
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
    private final boolean pitch;
    private final EventPublisher events;
    private final JdbcTemplate jdbc;

    public IdentitySeeder(IdentityStore store, ObjectMapper mapper, PlatformTransactionManager tm,
                          @Value("${propertyos.demo.enabled:true}") boolean demo, @Value("${propertyos.demo.pitch:false}") boolean pitch,
                          EventPublisher events, JdbcTemplate jdbc) {
        this.store = store;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
        this.pitch = pitch;
        this.events = events;
        this.jdbc = jdbc;
    }

    /**
     * On startup: in demo mode creates the sample companies, users and roles when the database is empty; otherwise only synchronises the feature catalogue into existing roles.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) {
            seedPitchIfEnabled();   // writes nothing outside demo mode; it only warns if the switch was left on
            syncCatalog();
            return;
        }
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                if (store.countOrgs() > 0) return;
                for (DemoData.Org o : DemoData.orgs()) store.insertOrg(o.id(), o.name(), o.legalName(), o.state(), o.plan());
                for (DemoData.Org o : DemoData.orgs()) seedOrg(o.id());
                log.info("Seeded identity demo data");
            });
            return null;
        });
        seedPitchIfEnabled();
        syncCatalog();
    }

    /**
     * The pitch environment ({@link PitchData}): only when demo mode and the pitch switch (DEMO_PITCH) are both on. Safe to run again: the Starter company, every
     * user and every invoice is created only if it is missing. A failure here is logged and never stops the service from starting.
     */
    public void seedPitchIfEnabled() {
        if (!PitchData.enabled(demo, pitch)) {
            if (pitch) log.warn("DEMO_PITCH is on but demo mode is off, so no pitch data is written.");
            return;
        }
        try {
            UserContextHolder.runAsSystem(null, () -> {
                tx.executeWithoutResult(s -> seedPitch());
                return null;
            });
            log.info("Pitch data ready (identity)");
        } catch (RuntimeException e) {
            log.error("Pitch data could not be written (identity): {}", e.getMessage(), e);
        }
    }

    private void seedPitch() {
        ensurePitchOrg();
        seedPitchUsers(null);
        // Summit Ridge's Austin broker and leasing agent work the new Austin building too.
        UUID riverside = PitchData.RIVERSIDE.property().id();
        for (String key : List.of("marcus", "jordan")) {
            UUID user = DemoData.id("user:" + key);
            if (store.user(user).isEmpty()) continue;
            Set<UUID> props = new LinkedHashSet<>(store.userPropertyIds(user));
            if (props.add(riverside)) store.setUserProperties(user, DemoData.SUMMIT.id(), props);
        }
        // Two boosts were paid for through platform billing: one running now, one finished. Their invoices are paid, so "boost revenue" has something to show.
        for (PitchData.Boost b : PitchData.boosts()) {
            jdbc.update("""
                    insert into identity.platform_invoice (id, org_id, number, kind, description, amount, paid, due_date, status, created_by, created_at)
                    values (?,?,?,'BOOST',?,?,?,current_date - ?,'PAID','Pitch data', now() - make_interval(days => ?)) on conflict do nothing""",
                    b.invoiceId(), DemoData.SUMMIT.id(), b.invoiceNumber(), "Listing boost, " + b.days() + " days: " + PitchData.listing(b.listingKey()).title(), b.amount(), b.amount(),
                    b.startedDaysAgo(), b.startedDaysAgo());
            jdbc.update("""
                    insert into identity.platform_payment (id, org_id, invoice_id, amount, method, last4, reference, status, recorded_by, created_at)
                    values (?,?,?,?,'CARD','4242',?,'SUCCEEDED','Pitch data', now() - make_interval(days => ?)) on conflict do nothing""",
                    DemoData.id("pitch:payment:" + b.invoiceNumber()), DemoData.SUMMIT.id(), b.invoiceId(), b.amount(), "PAY-" + b.invoiceNumber().replace("PO-", ""), b.startedDaysAgo());
        }
    }

    /** Makes the Starter company, with the same default roles every company gets, and tells the rest of the platform it exists (so it gets its automation rules). Only the first time. */
    private void ensurePitchOrg() {
        DemoData.Org o = PitchData.CEDAR;
        if (store.org(o.id()).isPresent()) return;
        store.insertOrg(o.id(), o.name(), o.legalName(), o.state(), o.plan());
        seedOrg(o.id());
        events.publish(o.id(), "OrganizationOnboarded", "Organization", o.id(), Map.of("name", o.name()));
    }

    /** Adds the pitch people for one company (or all of them when {@code org} is null) if they are missing. They sign in with the demo password like everyone else. */
    private void seedPitchUsers(UUID org) {
        for (DemoData.User u : PitchData.users()) {
            if (org != null && !u.orgId().equals(org)) continue;
            if (store.userByEmail(u.email()).isPresent()) continue;
            String partyType = "TENANT".equals(u.roleKey()) ? "TENANT" : null;
            store.insertUser(u.id(), u.orgId(), u.email(), u.name(), u.title(), null, partyType, u.partyId(), u.persona());
            store.setUserRoles(u.id(), u.orgId(), List.of(DemoData.id("role:" + u.orgId() + ":" + u.roleKey())));
            if (!u.propertyIds().isEmpty()) store.setUserProperties(u.id(), u.orgId(), u.propertyIds());
        }
    }

    /** Offers features added since an organisation was created to its default roles, once, so existing roles gain the new rows on the Roles screen. */
    void syncCatalog() {
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                for (Map<String, Object> o : store.orgs()) {
                    UUID orgId = (UUID) o.get("id");
                    if (orgId.equals(DemoData.PLATFORM.id())) continue;
                    if (!store.featureSynced(orgId, "owner-view:v1")) {
                        // Anyone who could see properties could see owners before owners had their own permission; keep that for custom roles too.
                        store.grantOwnerViewToPropertyViewers(orgId);
                        store.markFeatureSynced(orgId, "owner-view:v1");
                    }
                    if (!store.featureSynced(orgId, "branding:v1")) {
                        // Companies created before the branding defaults were made generic keep what they were showing.
                        ObjectNode over = overridesOf(orgId);
                        if (!over.has("branding")) {
                            ObjectNode b = over.putObject("branding");
                            b.put("companyName", String.valueOf(o.get("name")));
                            if (orgId.equals(DemoData.SUMMIT.id())) {
                                b.put("portalDomain", "residents.summitridgepg.com");
                                b.put("emailSender", "noreply@summitridgepg.com");
                            }
                            store.saveConfig(orgId, over.toString(), "branding", "Branding kept as shown before defaults became generic", "System");
                        }
                        store.markFeatureSynced(orgId, "branding:v1");
                    }
                    for (String feature : DefaultRoles.SYNCED_FEATURES) {
                        if (store.featureSynced(orgId, feature)) continue;
                        for (Map<String, Object> r : store.systemRoles(orgId)) {
                            DefaultRoles.byKey((String) r.get("role_key")).ifPresent(t -> t.grants().stream().filter(g -> g.feature().equals(feature))
                                    .forEach(g -> store.addGrantIfMissing((UUID) r.get("id"), orgId, feature, g.action(), g.maxAmount())));
                        }
                        store.markFeatureSynced(orgId, feature);
                    }
                    for (String field : DefaultRoles.SYNCED_FIELDS) {
                        String key = "field:" + field;
                        if (store.featureSynced(orgId, key)) continue;
                        for (Map<String, Object> r : store.systemRoles(orgId))
                            DefaultRoles.byKey((String) r.get("role_key")).map(t -> t.fieldRules().get(field))
                                    .ifPresent(access -> store.addFieldRuleIfMissing((UUID) r.get("id"), field, access));
                        store.markFeatureSynced(orgId, key);
                    }
                }
            });
            return null;
        });
    }

    private ObjectNode overridesOf(UUID orgId) {
        try {
            return (ObjectNode) mapper.readTree(String.valueOf(store.config(orgId).map(c -> c.get("config")).orElse("{}")));
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
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
        if (orgId.equals(DemoData.SUMMIT.id())) {
            ObjectNode b = overrides.putObject("branding");
            b.put("companyName", "Summit Ridge Property Group");
            b.put("portalDomain", "residents.summitridgepg.com");
            b.put("emailSender", "noreply@summitridgepg.com");
        }
        if (orgId.equals(DemoData.HARBOR.id())) {
            ObjectNode b = overrides.putObject("branding");
            b.put("companyName", "Harborview Residential");
            b.put("primaryColor", "#123C4A");
            b.put("accentColor", "#E07A5F");
        }
        store.saveConfig(orgId, overrides.toString(), "*", "Organisation onboarded", "System");
    }

    // Demo reset: wipe and reseed this organisation's identity data.
    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "identity.demo-reset";
    }

    /**
     * Event types this handler wants.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    /**
     * Rebuilds the demo company's identity data when a demo reset is requested.
     */
    @Override
    public void handle(DomainEvent event) {
        UUID orgId = event.orgId();
        UserContextHolder.runAsSystem(null, () -> {
            store.deleteOrgData(orgId);
            seedOrg(orgId);
            if (PitchData.enabled(demo, pitch) && PitchData.orgIds().contains(orgId)) tx.executeWithoutResult(s -> seedPitchUsers(orgId));
            return null;
        });
        log.info("Identity data reset for org {}", orgId);
    }
}
