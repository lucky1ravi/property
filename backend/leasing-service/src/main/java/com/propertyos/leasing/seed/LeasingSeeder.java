package com.propertyos.leasing.seed;

import com.propertyos.common.demo.DemoData;
import com.propertyos.common.demo.PitchData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.events.EventPublisher;
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
    private final boolean pitch;
    private final EventPublisher events;

    public LeasingSeeder(Db db, PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demo,
                         @Value("${propertyos.demo.pitch:false}") boolean pitch, EventPublisher events) {
        this.db = db;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
        this.pitch = pitch;
        this.events = events;
    }

    /**
     * On startup in demo mode, creates the sample portfolio, listings, applications and leases when the database is empty.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) {
            seedPitchIfEnabled();   // writes nothing outside demo mode; it only warns if the switch was left on
            return;
        }
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                Long n = db.value(Long.class, "select count(*) from leasing.owner");
                if (n != null && n > 0) return;
                for (UUID org : List.of(DemoData.SUMMIT.id(), DemoData.HARBOR.id())) seedOrg(org);
                log.info("Seeded leasing demo data");
            });
            return null;
        });
        seedPitchIfEnabled();
    }

    /**
     * The pitch environment ({@link PitchData}): only when demo mode and the pitch switch (DEMO_PITCH) are both on. Safe to run again: every row has a fixed id and is
     * inserted only if it is missing. A failure here is logged and never stops the service from starting.
     */
    public void seedPitchIfEnabled() {
        if (!PitchData.enabled(demo, pitch)) {
            if (pitch) log.warn("DEMO_PITCH is on but demo mode is off, so no pitch data is written.");
            return;
        }
        try {
            UserContextHolder.runAsSystem(null, () -> {
                tx.executeWithoutResult(s -> {
                    for (UUID org : PitchData.orgIds()) seedPitchOrg(org);
                });
                return null;
            });
            log.info("Pitch data ready (leasing)");
        } catch (RuntimeException e) {
            log.error("Pitch data could not be written (leasing): {}", e.getMessage(), e);
        }
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "leasing.demo-reset";
    }

    /**
     * Event types this handler wants.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    /**
     * Rebuilds the demo company's leasing data when a demo reset is requested.
     */
    @Override
    public void handle(DomainEvent event) {
        UUID org = event.orgId();
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> {
                // Children before parents: a boost, a campaign's leads and applications and the reviews all point at listings, campaigns or properties.
                for (String t : List.of("lease", "application", "showing", "lead", "listing_boost", "listing", "campaign", "review", "person", "unit", "property", "owner", "boost_company_price", "boost_promo"))
                    db.update("delete from leasing." + t + " where org_id = ?", org);
                seedOrg(org);
                if (PitchData.enabled(demo, pitch) && PitchData.orgIds().contains(org)) seedPitchOrg(org);
            });
            return null;
        });
        log.info("Leasing data reset for org {}", org);
    }

    /**
     * Stock photos used for demo listings.
     */
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

    // ----------------------------------------------------------------------------------------------- the pitch environment

    /** Writes one company's part of the pitch data. Every insert is "if missing" on a fixed id, so running it again changes nothing, except that the running boost's window is moved forward. */
    private void seedPitchOrg(UUID org) {
        OffsetDateTime now = OffsetDateTime.now();
        LocalDate today = LocalDate.now();
        if (org.equals(PitchData.CEDAR.id())) {
            DemoData.Owner o = PitchData.DOLORES;
            db.update("""
                    insert into leasing.owner (id, org_id, name, contact, email, phone, approval_limit, management_fee_pct, bank_last4) values (?,?,?,?,?,?,?,?,?)
                    on conflict do nothing""", o.id(), org, o.name(), o.contact(), o.email(), o.phone(), o.approvalLimit(), o.managementFeePercent(), o.bankLast4());
        }
        for (PitchData.Building b : PitchData.buildings(org)) {
            DemoData.Property p = b.property();
            db.update("""
                    insert into leasing.property (id, org_id, owner_id, name, street, city, state, zip, lat, lng, type, year_built, amenities, photo_url, photos)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?::jsonb) on conflict do nothing""",
                    p.id(), org, p.ownerId(), p.name(), p.street(), p.city(), p.state(), p.zip(), p.lat(), p.lng(), p.type(), p.yearBuilt(), db.json(p.amenities()), p.photo(),
                    db.json(gallery(p.photo(), p.id())));
            for (DemoData.Unit u : b.units()) {
                boolean resident = PitchData.LEASE_LISTING.equals(b.key() + ":" + u.number());
                db.update("""
                        insert into leasing.unit (id, org_id, property_id, owner_id, number, beds, baths, sqft, market_rent, current_rent, status, vacant_since)
                        values (?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing""",
                        u.id(), org, p.id(), p.ownerId(), u.number(), u.beds(), BigDecimal.valueOf(u.baths()), u.sqft(), u.marketRent(), resident ? u.marketRent() : null,
                        resident ? "OCCUPIED" : "VACANT", resident ? null : today.minusDays(5 + Math.abs(u.number().hashCode() % 20)));
            }
        }
        for (PitchData.Listing l : PitchData.listings()) {
            if (!l.orgId().equals(org)) continue;
            DemoData.Property p = l.property();
            boolean published = "LIVE".equals(l.status()) || "LEASED".equals(l.status());
            db.update("""
                    insert into leasing.listing (id, org_id, unit_id, property_id, owner_id, title, description, rent, deposit, available_date, photos, highlights,
                        status, published_at, views, enquiries, created_by)
                    values (?,?,?,?,?,?,?,?,?,?,?::jsonb,?::jsonb,?,?,?,?,?) on conflict do nothing""",
                    l.id(), org, l.unit().id(), p.id(), p.ownerId(), l.title(), l.description(), l.unit().marketRent(), l.unit().marketRent(),
                    "LEASED".equals(l.status()) ? today.minusDays(20) : today.plusDays(10), db.json(l.photos()), db.json(l.highlights()), l.status(),
                    published ? now.minusDays(l.liveDaysAgo()) : null, l.views(), l.enquiries(), "Pitch data");
        }
        for (PitchData.Review r : PitchData.reviews(org)) {
            db.update("""
                    insert into leasing.review (id, org_id, subject_type, subject_id, property_id, unit_id, owner_id, tenant_id, rater_user_id, rater_name, stars, comment,
                        created_at, updated_at, reply, reply_by, replied_at, ai_score, ai_source, ai_reasons)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'AI',?) on conflict do nothing""",
                    r.id(), org, r.subjectType(), r.subjectId(), r.propertyId(), r.unitId(), r.ownerId(), r.tenantId(), r.raterUserId(), r.raterName(), r.stars(), r.comment(),
                    now.minusDays(r.daysAgo()), now.minusDays(r.daysAgo()), r.reply(), r.replyBy(), r.replyDaysAgo() == null ? null : now.minusDays(r.replyDaysAgo()), r.aiScore(), r.aiReasons());
        }
        if (org.equals(DemoData.SUMMIT.id())) seedPitchSummit(org, now, today);
        log.info("Pitch data written for org {}", org);
    }

    /** Summit Ridge's part: two campaigns with leads at every stage, a lead ready to apply, a resident moving in with open charges, and a running boost. */
    private void seedPitchSummit(UUID org, OffsetDateTime now, LocalDate today) {
        for (PitchData.Campaign c : PitchData.campaigns()) {
            db.update("""
                    insert into leasing.campaign (id, org_id, name, channel, status, spend, starts_on, ends_on, public_key, secret, tracking_code, created_by, created_at)
                    values (?,?,?,?,'ACTIVE',?,?,?,?,?,?,?,?) on conflict do nothing""",
                    c.id(), org, c.name(), c.channel(), c.spend(), today.minusDays(c.startedDaysAgo()), today.plusDays(c.endsInDays()), c.publicKey(), c.secret(), c.trackingCode(),
                    "Sarah Mitchell", now.minusDays(c.startedDaysAgo()));
        }
        // People who went on to apply need a person record first. Jamie is the resident moving in.
        for (PitchData.Application a : PitchData.applications()) {
            String[] nm = a.name().split(" ");
            db.update("""
                    insert into leasing.person (id, org_id, first_name, last_name, email, phone, annual_income, employer, months_employed)
                    values (?,?,?,?,?,?,?,?,?) on conflict do nothing""", a.personId(), org, nm[0], nm[1], a.lead().email(), a.lead().phone(), a.annualIncome(), a.employer(), 30);
        }
        for (PitchData.Lead l : PitchData.leads()) {
            DemoData.Property p = l.listing().property();
            OffsetDateTime at = now.minusDays(l.daysAgo()).minusHours(2 + Math.abs(l.name().hashCode() % 9));
            String reply = "Hi " + l.firstName() + ", thanks for your interest in " + l.listing().title() + ". It's available now at $" + l.listing().unit().marketRent().toBigInteger()
                    + " per month. Would you like to tour it this week?";
            db.update("""
                    insert into leasing.lead (id, org_id, listing_id, property_id, owner_id, name, email, phone, message, source, status, ai_reply, ai_score, ai_intent,
                        responded_at, created_at, updated_at, campaign_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) on conflict do nothing""",
                    l.id(), org, l.listing().id(), p.id(), p.ownerId(), l.name(), l.email(), l.phone(), l.message(), l.campaign().channel(), l.status(), reply, l.score(), l.intent(),
                    "RESPONDED".equals(l.status()) ? at.plusHours(1) : null, at, at, l.campaign().id());
        }
        for (PitchData.Showing sh : PitchData.showings()) {
            PitchData.Lead l = sh.lead();
            DemoData.Property p = l.listing().property();
            db.update("""
                    insert into leasing.showing (id, org_id, listing_id, property_id, owner_id, lead_id, prospect_name, prospect_email, broker_user_id, broker_name, scheduled_at,
                        type, status, notes, created_at, updated_at)
                    values (?,?,?,?,?,?,?,?,?,?,?,'BROKER_LED',?,?,?,?) on conflict do nothing""",
                    sh.id(), org, l.listing().id(), p.id(), p.ownerId(), l.id(), l.name(), l.email(), DemoData.id("user:marcus"), "Marcus Johnson", now.plusDays(sh.dayOffset()).withHour(14).withMinute(0).withSecond(0).withNano(0),
                    sh.status(), sh.notes(), now.minusDays(Math.max(1, l.daysAgo())), now.minusDays(Math.max(0, -sh.dayOffset())));
        }
        for (PitchData.Application a : PitchData.applications()) {
            PitchData.Lead l = a.lead();
            PitchData.Listing li = l.listing();
            DemoData.Property p = li.property();
            boolean decided = "APPROVED".equals(a.status());
            OffsetDateTime submitted = now.minusDays(a.daysAgo());
            db.update("""
                    insert into leasing.application (id, org_id, listing_id, unit_id, property_id, owner_id, applicant_id, applicant_name, applicant_email, unit_label,
                        status, monthly_income, employer, months_employed, current_rent, occupants, move_in_date, credit_score, background_clear, evictions, fee_paid,
                        decision_reason, decided_by, decided_at, submitted_at, created_at, updated_at, source, campaign_id)
                    values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,0,true,?,?,?,?,?,?,?,?) on conflict do nothing""",
                    a.id(), org, li.id(), li.unit().id(), p.id(), p.ownerId(), a.personId(), a.name(), l.email(), p.name() + " " + li.unit().number(), a.status(),
                    BigDecimal.valueOf(a.annualIncome() / 12.0).setScale(2, java.math.RoundingMode.HALF_UP), a.employer(), 30, li.unit().marketRent().subtract(BigDecimal.valueOf(100)),
                    1, today.plusDays(30), a.creditScore(), a.creditScore() == null ? null : Boolean.TRUE,
                    decided ? "Income is 3.4 times the rent and screening was clean." : null, decided ? "Marcus Johnson" : null, decided ? submitted.plusDays(1) : null, submitted, submitted, submitted,
                    l.campaign().channel(), l.campaign().id());
        }
        seedPitchLease(org, now, today);
        for (PitchData.Boost b : PitchData.boosts()) {
            PitchData.Listing li = PitchData.listing(b.listingKey());
            DemoData.Property p = li.property();
            OffsetDateTime start = now.minusDays(b.startedDaysAgo());
            if ("ACTIVE".equals(b.status())) {
                // Moved forward on every run so the boost is still running when the pitch is re-run later; skipped if the presenter has bought another boost for this home.
                db.update("""
                        insert into leasing.listing_boost (id, org_id, listing_id, property_id, owner_id, days, starts_at, ends_at, amount, status, invoice_id, created_by, list_price, source)
                        select ?,?,?,?,?,?,?,?,?,'ACTIVE',?,?,?,'PURCHASE'
                        where not exists (select 1 from leasing.listing_boost x where x.listing_id = ? and x.status in ('PENDING','ACTIVE') and x.id <> ?)
                        on conflict (id) do update set starts_at = excluded.starts_at, ends_at = excluded.ends_at, status = 'ACTIVE', ending_notified = false, updated_at = now()""",
                        b.id(), org, li.id(), p.id(), p.ownerId(), b.days(), start, start.plusDays(b.days()), b.amount(), b.invoiceId(), "Sarah Mitchell", b.amount(), li.id(), b.id());
            } else {
                db.update("""
                        insert into leasing.listing_boost (id, org_id, listing_id, property_id, owner_id, days, starts_at, ends_at, amount, status, invoice_id, created_by, list_price, source)
                        values (?,?,?,?,?,?,?,?,?,'EXPIRED',?,?,?,'PURCHASE') on conflict do nothing""",
                        b.id(), org, li.id(), p.id(), p.ownerId(), b.days(), start, start.plusDays(b.days()), b.amount(), b.invoiceId(), "Sarah Mitchell", b.amount());
            }
        }
    }

    /**
     * Jamie Rivera's lease, from the approved Facebook application, starting a few days from now. The billing service then makes the move-in charges (deposit and first
     * month's rent) itself, because this announces the lease the same way the product does when a lease is activated. It is announced only the first time.
     */
    private void seedPitchLease(UUID org, OffsetDateTime now, LocalDate today) {
        PitchData.Listing li = PitchData.listing(PitchData.LEASE_LISTING);
        DemoData.Property p = li.property();
        DemoData.Unit u = li.unit();
        LocalDate start = today.plusDays(PitchData.LEASE_STARTS_IN_DAYS), end = start.plusYears(1).minusDays(1);
        String name = "Jamie Rivera", address = p.street() + ", " + p.city() + ", " + p.state() + " " + p.zip();
        var rules = db.tree(Map.of("name", p.state(), "depositCapMonths", 2, "lateFeeGraceDays", 5, "depositRefundDays", 30, "noticeDays", 30));
        var fees = db.tree(Map.of("lateFee", Map.of("type", "PERCENT", "percent", 5)));
        LeaseDocument.Result doc = LeaseDocument.generate(DemoData.SUMMIT.name(), name, p.name(), address, "Unit " + u.number(), start, end, u.marketRent(), u.marketRent(), p.state(), rules, fees, "none");
        int made = db.update("""
                insert into leasing.lease (id, org_id, unit_id, property_id, owner_id, tenant_id, application_id, status, start_date, end_date, rent, deposit, state,
                    terms, document_text, tenant_signature, tenant_signed_at, manager_signature, manager_signed_at, unit_label, property_name, property_address, tenant_name, tenant_email)
                values (?,?,?,?,?,?,?,'ACTIVE',?,?,?,?,?,?::jsonb,?,?,?,?,?,?,?,?,?,?) on conflict do nothing""",
                PitchData.LEASE_ID, org, u.id(), p.id(), p.ownerId(), PitchData.JAMIE_ID, DemoData.id("pitch:application:jamie"), start, end, u.marketRent(), u.marketRent(), p.state(),
                db.json(doc.terms()), doc.text(), name, now.minusDays(3), "Marcus Johnson", now.minusDays(2), "Unit " + u.number(), p.name(), address, name, PitchData.JAMIE_EMAIL);
        if (made == 0) return;
        Map<String, Object> payload = new HashMap<>();
        payload.put("unitId", u.id().toString());
        payload.put("tenantId", PitchData.JAMIE_ID.toString());
        payload.put("rent", u.marketRent().toString());
        payload.put("deposit", u.marketRent().toString());
        payload.put("propertyId", p.id().toString());
        payload.put("ownerId", p.ownerId().toString());
        payload.put("startDate", start.toString());
        payload.put("tenantName", name);
        payload.put("unitLabel", "Unit " + u.number());
        payload.put("propertyName", p.name());
        payload.put("state", p.state());
        events.publish(org, "LeaseActivated", "Lease", PitchData.LEASE_ID, payload);
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
