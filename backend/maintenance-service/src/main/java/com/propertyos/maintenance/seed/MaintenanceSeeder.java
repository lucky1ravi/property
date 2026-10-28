package com.propertyos.maintenance.seed;

import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/** Seeds vendors and a realistic spread of work orders in every state. */
@Component
public class MaintenanceSeeder implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(MaintenanceSeeder.class);
    private final Db db;
    private final TransactionTemplate tx;
    private final boolean demo;

    public MaintenanceSeeder(Db db, PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) return;
        for (DemoData.Org org : List.of(DemoData.SUMMIT, DemoData.HARBOR)) {
            UserContextHolder.runAsSystem(org.id(), () -> {
                tx.executeWithoutResult(s -> {
                    if (db.value(Long.class, "select count(*) from maintenance.work_order") > 0) return;
                    seed(org.id());
                    log.info("Seeded maintenance demo data for {}", org.name());
                });
                return null;
            });
        }
    }

    @Override
    public String subscriber() {
        return "maintenance.demo-reset";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    @Override
    public void handle(DomainEvent event) {
        UUID org = event.orgId();
        UserContextHolder.runAsSystem(org, () -> {
            tx.executeWithoutResult(s -> {
                for (String t : List.of("work_order_comment", "work_order", "recurring_task", "vendor")) db.update("delete from maintenance." + t + " where org_id = ?", org);
                seed(org);
            });
            return null;
        });
    }

    private record Case(String tenantKey, String vendorKey, String title, String desc, String cat, String pri, String status, int estimate, Integer cost, int ageHours, Integer rating) {
    }

    private void seed(UUID org) {
        for (DemoData.Vendor v : DemoData.VENDORS) {
            if (!v.orgId().equals(org)) continue;
            db.update("""
                    insert into maintenance.vendor (id, org_id, name, contact, phone, email, trades, cities, rating, jobs_completed, avg_job_cost, avg_response_hours, insurance_expires)
                    values (?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)""", v.id(), org, v.name(), v.contact(), v.phone(), v.email(), db.json(v.trades()), db.json(v.cities()),
                    v.rating(), v.jobsCompleted(), v.avgJobCost(), 3 + Math.abs(v.name().hashCode() % 9), LocalDate.now().plusMonths(8));
        }
        List<Case> cases = List.of(
                new Case("michael", null, "Kitchen faucet dripping", "Steady drip from the kitchen faucet, getting worse.", "PLUMBING", "NORMAL", "TRIAGED", 280, null, 5, null),
                new Case("jessica", "austinhvac", "AC not cooling", "Air conditioner runs but the apartment stays at 84 degrees.", "HVAC", "HIGH", "ASSIGNED", 420, null, 20, null),
                new Case("daniel", "brightspark", "Outlet sparking in bedroom", "I saw a spark when plugging in a lamp. Outlet is warm.", "ELECTRICAL", "EMERGENCY", "IN_PROGRESS", 520, null, 3, null),
                new Case("sophia", null, "Dishwasher leaking", "Water on the floor after every cycle.", "APPLIANCE", "HIGH", "SUBMITTED", 240, null, 0, null),
                new Case("christopher", "lonestar", "Water heater replacement", "Water heater is 14 years old and rusting at the base.", "PLUMBING", "HIGH", "AWAITING_APPROVAL", 1850, null, 30, null),
                new Case("amanda", "lonestar", "Clogged bathroom drain", "Shower drains slowly.", "PLUMBING", "NORMAL", "COMPLETED", 190, 175, 60, null),
                new Case("matthew", "austinhvac", "Thermostat not responding", "Thermostat screen is blank.", "HVAC", "NORMAL", "CLOSED", 160, 145, 150, 5),
                new Case("olivia", "pestaway", "Ants in kitchen", "Seeing ants along the counter every morning.", "PEST", "LOW", "SCHEDULED", 180, null, 40, null),
                new Case("ethan", "coastal", "Garbage disposal jammed", "Humming noise but not spinning.", "APPLIANCE", "NORMAL", "CLOSED", 220, 205, 210, 4),
                new Case("ashley", "brightspark", "Hallway light flickering", "Light flickers when the AC turns on.", "ELECTRICAL", "NORMAL", "ASSIGNED", 310, null, 12, null),
                new Case("megan", "emerald", "Bathroom exhaust fan loud", "Fan makes a grinding noise.", "GENERAL", "LOW", "CLOSED", 140, 130, 300, 5),
                new Case("joshua", null, "Heater not working", "No heat from the unit in the living room.", "HVAC", "HIGH", "TRIAGED", 440, null, 8, null),
                new Case("lauren", "cascade", "Furnace annual service", "Annual service and filter replacement.", "HVAC", "LOW", "SCHEDULED", 190, null, 90, null),
                new Case("andrew", "emerald", "Toilet running constantly", "Toilet runs after flushing.", "PLUMBING", "NORMAL", "COMPLETED", 160, 150, 50, null),
                new Case("grace", "coastal", "Dripping kitchen tap", "Drip in kitchen sink.", "PLUMBING", "NORMAL", "IN_PROGRESS", 200, null, 26, null)
        );
        List<DemoData.Lease> leases = DemoData.leases();
        for (Case c : cases) {
            DemoData.Tenant t = DemoData.tenant(c.tenantKey());
            if (!t.orgId().equals(org)) continue;
            DemoData.Lease l = leases.stream().filter(x -> x.tenantId().equals(t.id())).findFirst().orElseThrow();
            DemoData.Property p = DemoData.property(l.propertyId());
            DemoData.Unit u = DemoData.units().stream().filter(x -> x.id().equals(l.unitId())).findFirst().orElseThrow();
            DemoData.Vendor v = c.vendorKey() == null ? null : DemoData.vendor(c.vendorKey());
            OffsetDateTime created = OffsetDateTime.now(ZoneOffset.UTC).minusHours(c.ageHours());
            int sla = switch (c.pri()) { case "EMERGENCY" -> 4; case "HIGH" -> 24; case "LOW" -> 168; default -> 72; };
            boolean done = List.of("COMPLETED", "CLOSED").contains(c.status());
            UUID id = DemoData.id("wo:" + org + ":" + c.tenantKey());
            Map<String, Object> triage = Map.of("source", "AI", "summary", "Classified as " + c.cat().toLowerCase() + ", " + c.pri().toLowerCase() + " priority.",
                    "safetyRisk", "EMERGENCY".equals(c.pri()), "confidence", 0.88, "tips", List.of());
            db.update("""
                    insert into maintenance.work_order (id, org_id, property_id, owner_id, unit_id, tenant_id, vendor_id, property_name, unit_label, tenant_name, title, description,
                        category, priority, status, source, estimate, final_cost, vendor_name, sla_due_at, ai_triage, completed_at, rating, created_by, created_at, updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'TENANT',?,?,?,?,?::jsonb,?,?,?,?,?)""",
                    id, org, p.id(), p.ownerId(), u.id(), t.id(), v == null ? null : v.id(), p.name(), "Unit " + u.number(), t.name(), c.title(), c.desc(),
                    c.cat(), c.pri(), c.status(), BigDecimal.valueOf(c.estimate()), c.cost() == null ? null : BigDecimal.valueOf(c.cost()), v == null ? null : v.name(),
                    created.plusHours(sla), db.json(triage), done ? created.plusHours(Math.max(2, c.ageHours() / 2)) : null, c.rating(), t.name(), created, created);
            db.update("insert into maintenance.work_order_comment (id, org_id, work_order_id, property_id, owner_id, tenant_id, vendor_id, author, body) values (?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), org, id, p.id(), p.ownerId(), t.id(), v == null ? null : v.id(), t.name(), "Submitted through the resident portal. Happy to be home between 9 and 5.");
        }
        for (DemoData.Property p : DemoData.properties()) {
            if (!p.orgId().equals(org)) continue;
            db.update("insert into maintenance.recurring_task (id, org_id, property_id, owner_id, property_name, title, category, every_months, next_due) values (?,?,?,?,?,?,?,?,?)",
                    DemoData.id("rec:hvac:" + p.id()), org, p.id(), p.ownerId(), p.name(), "Quarterly HVAC filter change", "HVAC", 3, LocalDate.now().plusDays(12 + Math.abs(p.name().hashCode() % 40)));
        }
    }
}
