package com.propertyos.leasing.seed;

import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.leasing.service.LeaseDocument;
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
import java.util.*;

/** Seeds the demo portfolio, listings, enquiries, applications and leases. Handles demo resets. */
@Component
public class LeasingSeeder implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(LeasingSeeder.class);
    private final Db db;
    private final TransactionTemplate tx;
    private final boolean demo;

    public LeasingSeeder(Db db, PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) return;
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                Long n = db.value(Long.class, "select count(*) from leasing.owner");
                if (n != null && n > 0) return;
                for (UUID org : List.of(DemoData.SUMMIT.id(), DemoData.HARBOR.id())) seedOrg(org);
                log.info("Seeded leasing demo data");
            });
            return null;
        });
    }

    @Override
    public String subscriber() {
        return "leasing.demo-reset";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    @Override
    public void handle(DomainEvent event) {
        UUID org = event.orgId();
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                for (String t : List.of("lease", "application", "showing", "lead", "listing", "person", "unit", "property", "owner"))
                    db.update("delete from leasing." + t + " where org_id = ?", org);
                seedOrg(org);
            });
            return null;
        });
        log.info("Leasing data reset for org {}", org);
    }

    private static final List<String> GALLERY = List.of(
            "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?w=1200&q=80", "https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=1200&q=80",
            "https://images.unsplash.com/photo-1570129477492-45c003edd2be?w=1200&q=80", "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=1200&q=80",
            "https://images.unsplash.com/photo-1460317442991-0ec209397118?w=1200&q=80", "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=1200&q=80",
            "https://images.unsplash.com/photo-1494526585095-c41746248156?w=1200&q=80");

    /** Cover photo first, then three more from the shared pool so every property has a gallery. */
    static List<String> gallery(String cover, UUID id) {
        int h = Math.abs(id.toString().hashCode());
        List<String> out = new ArrayList<>();
        if (cover != null) out.add(cover);
        for (int k : new int[]{1, 3, 5}) {
            String u = GALLERY.get((h + k) % GALLERY.size());
            if (!out.contains(u)) out.add(u);
        }
        return out;
    }

    private void seedOrg(UUID org) {
        for (DemoData.Owner o : DemoData.owners()) {
            if (!o.orgId().equals(org)) continue;
            db.update("insert into leasing.owner (id, org_id, name, contact, email, phone, approval_limit, management_fee_pct, bank_last4) values (?,?,?,?,?,?,?,?,?)",
                    o.id(), org, o.name(), o.contact(), o.email(), o.phone(), o.approvalLimit(), o.managementFeePercent(), o.bankLast4());
        }
        Map<UUID, DemoData.Property> props = new HashMap<>();
        for (DemoData.Property p : DemoData.properties()) {
            if (!p.orgId().equals(org)) continue;
            props.put(p.id(), p);
            db.update("""
                    insert into leasing.property (id, org_id, owner_id, name, street, city, state, zip, lat, lng, type, year_built, amenities, photo_url, photos)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb)""",
                    p.id(), org, p.ownerId(), p.name(), p.street(), p.city(), p.state(), p.zip(), p.lat(), p.lng(), p.type(), p.yearBuilt(),
                    db.json(p.amenities()), p.photo(), db.json(gallery(p.photo(), p.id())));
        }
        Set<UUID> leased = new HashSet<>();
        Map<UUID, DemoData.Lease> leaseByUnit = new HashMap<>();
        for (DemoData.Lease l : DemoData.leases()) {
            if (!l.orgId().equals(org)) continue;
            leased.add(l.unitId());
            leaseByUnit.put(l.unitId(), l);
        }
        List<DemoData.Unit> units = new ArrayList<>();
        for (DemoData.Unit u : DemoData.units()) {
            DemoData.Property p = props.get(u.propertyId());
            if (p == null) continue;
            units.add(u);
            boolean occ = leased.contains(u.id());
            DemoData.Lease l = leaseByUnit.get(u.id());
            db.update("""
                    insert into leasing.unit (id, org_id, property_id, owner_id, number, beds, baths, sqft, market_rent, current_rent, status, vacant_since)
                    values (?,?,?,?,?,?,?,?,?,?,?,?)""",
                    u.id(), org, u.propertyId(), p.ownerId(), u.number(), u.beds(), BigDecimal.valueOf(u.baths()), u.sqft(), u.marketRent(),
                    occ ? l.rent() : null, occ ? "OCCUPIED" : "VACANT", occ ? null : LocalDate.now().minusDays(9 + Math.abs(u.number().hashCode() % 40)));
        }
        for (DemoData.Tenant t : DemoData.TENANTS) {
            if (!t.orgId().equals(org)) continue;
            db.update("""
                    insert into leasing.person (id, org_id, first_name, last_name, email, phone, annual_income, employer, months_employed)
                    values (?,?,?,?,?,?,?,?,?)""",
                    t.id(), org, t.firstName(), t.lastName(), t.email(), t.phone(), t.annualIncome(), t.employer(), 36);
        }
        Map<UUID, DemoData.Unit> unitById = new HashMap<>();
        units.forEach(u -> unitById.put(u.id(), u));
        for (DemoData.Lease l : leaseByUnit.values()) {
            DemoData.Unit u = unitById.get(l.unitId());
            DemoData.Property p = props.get(l.propertyId());
            DemoData.Tenant t = DemoData.TENANTS.stream().filter(x -> x.id().equals(l.tenantId())).findFirst().orElseThrow();
            var ctx = org.equals(DemoData.HARBOR.id()) ? "CA" : p.state();
            var rules = db.tree(Map.of("name", ctx, "depositCapMonths", 2, "lateFeeGraceDays", 5, "depositRefundDays", 30, "noticeDays", 30));
            var fees = db.tree(Map.of("lateFee", Map.of("type", "PERCENT", "percent", 5)));
            LeaseDocument.Result doc = LeaseDocument.generate(org.equals(DemoData.HARBOR.id()) ? DemoData.HARBOR.name() : DemoData.SUMMIT.name(),
                    t.name(), p.name(), p.street() + ", " + p.city() + ", " + p.state() + " " + p.zip(), "Unit " + u.number(),
                    l.start(), l.end(), l.rent(), l.deposit(), p.state(), rules, fees, "none");
            db.update("""
                    insert into leasing.lease (id, org_id, unit_id, property_id, owner_id, tenant_id, status, start_date, end_date, rent, deposit, state,
                        terms, document_text, tenant_signature, tenant_signed_at, manager_signature, manager_signed_at,
                        unit_label, property_name, property_address, tenant_name, tenant_email)
                    values (?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?)""",
                    l.id(), org, l.unitId(), l.propertyId(), l.ownerId(), l.tenantId(), l.start(), l.end(), l.rent(), l.deposit(), p.state(),
                    db.json(doc.terms()), doc.text(), t.name(), OffsetDateTime.now().minusMonths(6), "Property Manager", OffsetDateTime.now().minusMonths(6),
                    "Unit " + u.number(), p.name(), p.street() + ", " + p.city() + ", " + p.state() + " " + p.zip(), t.name(), t.email());
        }
        seedListingsAndApplications(org, units, props, leased);
    }

    private void seedListingsAndApplications(UUID org, List<DemoData.Unit> units, Map<UUID, DemoData.Property> props, Set<UUID> leased) {
        int i = 0;
        for (DemoData.Unit u : units) {
            if (leased.contains(u.id())) continue;
            DemoData.Property p = props.get(u.propertyId());
            String status = i == 0 ? "DRAFT" : i == 6 ? "PAUSED" : "LIVE";
            UUID lid = DemoData.id("listing:" + u.id());
            String title = (u.beds() == 0 ? "Studio" : u.beds() + "-bedroom") + " at " + p.name() + " · " + p.city() + ", " + p.state();
            String desc = "Unit " + u.number() + " is a " + u.sqft() + " sq ft " + (u.beds() == 0 ? "studio" : u.beds() + " bed") + ", " + u.baths()
                    + " bath home in " + p.city() + ". Amenities include " + String.join(", ", p.amenities().stream().limit(3).toList()) + ".";
            db.update("""
                    insert into leasing.listing (id, org_id, unit_id, property_id, owner_id, title, description, rent, deposit, available_date, photos, highlights,
                        status, published_at, views, enquiries, created_by)
                    values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?)""",
                    lid, org, u.id(), p.id(), p.ownerId(), title, desc, u.marketRent(), u.marketRent(), LocalDate.now().plusDays(14),
                    db.json(p.photo() == null ? List.of() : List.of(p.photo())), db.json(p.amenities().stream().limit(3).toList()),
                    status, "LIVE".equals(status) ? OffsetDateTime.now().minusDays(3 + i) : null, 40 + i * 17, 2 + i, "Alex Rivera");
            if ("LIVE".equals(status) && i < 5) {
                UUID leadId = DemoData.id("lead:" + lid);
                db.update("""
                        insert into leasing.lead (id, org_id, listing_id, property_id, owner_id, name, email, phone, message, source, status)
                        values (?,?,?,?,?,?,?,?,?,?,'NEW')""",
                        leadId, org, lid, p.id(), p.ownerId(), "Taylor Morgan " + i, "taylor.morgan" + i + "@example.com", "(555) 010-01" + (10 + i),
                        "Is this still available? We have a small dog and could move in next month.", "WEBSITE");
            }
            i++;
        }
        // Applications at varied stages on live listings
        String[] stages = {"SUBMITTED", "SCREENED", "OWNER_REVIEW", "DOCS_PENDING", "SCREENED"};
        String[] names = {"Priya Raman", "Marcus Bell", "Elena Ortiz", "Noah Fischer", "Hannah Cho"};
        int[] incomes = {96000, 71000, 128000, 54000, 88000};
        int[] credit = {742, 668, 781, 612, 705};
        int idx = 0;
        for (DemoData.Unit u : units) {
            if (leased.contains(u.id()) || idx >= stages.length) continue;
            DemoData.Property p = props.get(u.propertyId());
            UUID lid = DemoData.id("listing:" + u.id());
            String status = db.value(String.class, "select status from leasing.listing where id = ?", lid);
            if (!"LIVE".equals(status)) continue;
            String[] nm = names[idx].split(" ");
            UUID pid = DemoData.id("applicant:" + names[idx] + ":" + org);
            String email = nm[0].toLowerCase() + "." + nm[1].toLowerCase() + "@example.com";
            db.update("insert into leasing.person (id, org_id, first_name, last_name, email, phone, annual_income, employer, months_employed) values (?,?,?,?,?,?,?,?,?)",
                    pid, org, nm[0], nm[1], email, "(555) 020-01" + (10 + idx), incomes[idx], "Acme Corp", 24);
            boolean screened = !"SUBMITTED".equals(stages[idx]) && !"DOCS_PENDING".equals(stages[idx]);
            db.update("""
                    insert into leasing.application (id, org_id, listing_id, unit_id, property_id, owner_id, applicant_id, applicant_name, applicant_email, unit_label,
                        status, monthly_income, employer, months_employed, current_rent, occupants, move_in_date, credit_score, background_clear, evictions, fee_paid)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,true)""",
                    DemoData.id("app:" + pid + lid), org, lid, u.id(), p.id(), p.ownerId(), pid, names[idx], email, p.name() + " " + u.number(),
                    stages[idx], BigDecimal.valueOf(incomes[idx] / 12.0).setScale(2, java.math.RoundingMode.HALF_UP), "Acme Corp", 24,
                    u.marketRent().subtract(BigDecimal.valueOf(150)), 1 + idx % 3, LocalDate.now().plusDays(30),
                    screened ? credit[idx] : null, screened ? Boolean.TRUE : null, 0);
            idx++;
        }
    }
}
