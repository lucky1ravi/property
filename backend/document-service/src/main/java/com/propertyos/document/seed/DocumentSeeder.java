package com.propertyos.document.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.document.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.*;
import java.util.List;

/**
 * Demo library: insurance certificates, W-9s, agreements, applicant files, work order photos, vendor bills, notices
 * and signature requests, spread across personas so each sees a realistic set. Every file is clearly marked as a sample.
 * Waits until leasing and maintenance are up (it links files to their records), then seeds once per organisation.
 */
@Component
public class DocumentSeeder implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(DocumentSeeder.class);
    /**
     * Text put in every demo document so it cannot be mistaken for a real record.
     */
    private static final String SAMPLE = "Sample document generated for the PropertyOS demo. Not a real record.";
    private final Db db;
    private final ServiceClient client;
    private final DocumentService docs;
    private final LeaseDocuments leases;
    private final TransactionTemplate tx;
    private final boolean demo;

    public DocumentSeeder(Db db, ServiceClient client, DocumentService docs, LeaseDocuments leases, PlatformTransactionManager tm,
                          @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.db = db;
        this.client = client;
        this.docs = docs;
        this.leases = leases;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "documents.demo-reset";
    }

    /**
     * Event types this handler wants.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    /**
     * Clears demo documents on a reset; {@link #seedWhenReady()} recreates them once the other services have restored their records.
     */
    @Override
    public void handle(DomainEvent e) {
        // Clear now; the scheduler re-seeds shortly after, once the other services have restored their records.
        db.update("delete from document.signature_request where org_id = ?", e.orgId());
        db.update("delete from document.document where org_id = ?", e.orgId());
        db.update("delete from document.blob where org_id = ?", e.orgId());
        db.update("delete from document.seed_state where org_id = ?", e.orgId());
    }

    /**
     * Timer: in demo mode, creates sample documents once the records they attach to exist.
     */
    @Scheduled(fixedDelay = 30_000, initialDelay = 25_000)
    public void seedWhenReady() {
        if (!demo) return;
        for (DemoData.Org org : List.of(DemoData.SUMMIT, DemoData.HARBOR)) {
            try {
                UserContextHolder.runAsSystem(org.id(), () -> tx.execute(s -> {
                    if (db.value(Long.class, "select count(*) from document.seed_state where org_id = ?", org.id()) > 0) return null;
                    // Other services must be ready: their lists drive which applications and work orders get files.
                    JsonNode apps = client.get(ServiceClient.Service.LEASING, "/internal/leasing/applications-brief");
                    JsonNode wos = client.get(ServiceClient.Service.MAINTENANCE, "/internal/maintenance/work-orders-brief");
                    JsonNode vendors = client.get(ServiceClient.Service.MAINTENANCE, "/internal/maintenance/vendors-brief");
                    JsonNode leaseDocs = client.get(ServiceClient.Service.LEASING, "/internal/leasing/lease-documents");
                    if (org.id().equals(DemoData.SUMMIT.id()) && (apps == null || apps.isEmpty() || wos == null || wos.isEmpty())) return null;
                    seed(org.id(), apps, wos, vendors);
                    for (JsonNode l : leaseDocs) if (org.id().toString().equals(l.path("org_id").asText())) leases.sync(l);
                    db.update("insert into document.seed_state (org_id) values (?) on conflict do nothing", org.id());
                    log.info("Seeded demo documents for {}", org.name());
                    return null;
                }));
            } catch (Exception ex) {
                log.debug("Document seeding for {} deferred: {}", org.name(), ex.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ seed data

    private static Lookups.Links links(UUID property, UUID unit, UUID owner, UUID tenant, UUID vendor, String label) {
        String pname = property == null ? null : DemoData.properties().stream().filter(p -> p.id().equals(property)).map(DemoData.Property::name).findFirst().orElse(null);
        return new Lookups.Links(property, pname, unit, owner, tenant, vendor, label);
    }

    private static UUID user(String key) {
        return DemoData.id("user:" + key);
    }

    private static String userName(String key) {
        return DemoData.users().stream().filter(u -> u.id().equals(user(key))).map(DemoData.User::name).findFirst().orElse("PropertyOS");
    }

    private record Upload(String title, String category, String docType, String entityType, UUID entityId, Lookups.Links links,
                          boolean shareOwner, boolean shareTenant, boolean shareVendor, LocalDate expires, String review, String reviewNote,
                          String byKey, String byName, String byScope, String fileName, byte[] data, int daysAgo, String[] tags) {
    }

    private UUID put(UUID org, Upload u) {
        FileRules.Checked f = new FileRules.Checked(u.fileName(), u.fileName().substring(u.fileName().lastIndexOf('.') + 1),
                u.fileName().endsWith(".png") ? "image/png" : "application/pdf", u.data().length, FileRules.sha256(u.data()));
        UUID id = UUID.randomUUID();
        UUID by = u.byKey() == null ? null : user(u.byKey());
        String byName = u.byName() != null ? u.byName() : u.byKey() == null ? "PropertyOS" : userName(u.byKey());
        boolean sensitive = docs.categories().path(u.category()).path("sensitive").asBoolean(false);
        invokeInsert(id, org, u, f, by, byName, sensitive);
        if (u.reviewNote() != null) db.update("update document.document set review_note = ? where id = ?", u.reviewNote(), id);
        if (u.tags() != null && u.tags().length > 0) db.update("update document.document set tags = ?::text[] where id = ?", "{" + String.join(",", u.tags()) + "}", id);
        db.update("update document.document set created_at = now() - make_interval(days => ?), updated_at = now() - make_interval(days => ?) where id = ?", u.daysAgo(), u.daysAgo(), id);
        db.update("update document.document_version set created_at = now() - make_interval(days => ?) where document_id = ?", u.daysAgo(), id);
        db.update("insert into platform.audit_event (org_id, entity_type, entity_id, action, to_state, actor_id, actor_name, details, created_at) values (?, 'Document', ?, 'DOCUMENT_UPLOADED', 'ACTIVE', ?, ?, '{}'::jsonb, now() - make_interval(days => ?))",
                org, id, by, byName, u.daysAgo());
        return id;
    }

    private void invokeInsert(UUID id, UUID org, Upload u, FileRules.Checked f, UUID by, String byName, boolean sensitive) {
        docs.insert(id, org, u.title(), u.category(), u.docType(), null, "UPLOAD", u.entityType(), u.entityId(), u.links(), u.shareOwner(), u.shareTenant(), u.shareVendor(),
                sensitive, List.of(), u.expires(), u.review(), by, byName, u.byScope(), f, u.data(), "Uploaded");
    }

    private void seed(UUID org, JsonNode apps, JsonNode wos, JsonNode vendors) {
        LocalDate today = LocalDate.now();
        List<DemoData.Property> props = DemoData.properties().stream().filter(p -> p.orgId().equals(org)).toList();

        // Property records shared with owners: insurance policy, inspection report.
        int i = 0;
        for (DemoData.Property p : props) {
            String owner = DemoData.owner(p.ownerId()).map(DemoData.Owner::name).orElse("Owner");
            LocalDate exp = p.id().equals(DemoData.MUELLER.id()) ? today.plusDays(24) : today.plusDays(120 + i * 37);
            put(org, new Upload("Property insurance policy – " + p.name(), "INSURANCE", "Property insurance policy", "PROPERTY", p.id(),
                    links(p.id(), null, p.ownerId(), null, null, p.name()), true, false, false, exp, "NONE", null, "david", null, "ORG",
                    "insurance-policy-" + slug(p.name()) + ".pdf",
                    pdf("Homeowners / Dwelling Policy Declarations", "Named insured: " + owner, "Property: " + p.name() + ", " + p.street() + ", " + p.city() + ", " + p.state() + " " + p.zip(),
                            "Policy period: " + exp.minusYears(1) + " to " + exp, "Dwelling coverage: $" + (2 + i) + ",400,000", "Liability: $1,000,000 per occurrence", "Carrier: Lone Oak Mutual (sample)"),
                    40 + i, new String[]{"insurance"}));
            put(org, new Upload("Annual inspection – " + p.name(), "PROPERTY", "Inspection report", "PROPERTY", p.id(),
                    links(p.id(), null, p.ownerId(), null, null, p.name()), true, false, false, null, "NONE", null, org.equals(DemoData.SUMMIT.id()) ? "marcus" : "kevin", null, "PROPERTY",
                    "inspection-" + slug(p.name()) + ".pdf",
                    pdf("Annual property inspection", "Property: " + p.name(), "Inspected: " + today.minusDays(60 + i * 9), "Roof: good, 6+ years remaining", "Smoke and CO detectors: all units tested, 2 batteries replaced",
                            "Water heaters: within service life", "Exterior: minor caulking needed at north windows", "Overall condition: 8 / 10"),
                    60 + i * 9, new String[]{}));
            i++;
        }
        if (!org.equals(DemoData.SUMMIT.id())) return;

        // Bills for the owner's statement.
        put(org, new Upload("Austin Energy – common areas, " + today.minusMonths(1).getMonth().toString().charAt(0) + today.minusMonths(1).getMonth().toString().substring(1).toLowerCase(), "BILL", "Utility bill", "PROPERTY", DemoData.DOMAIN.id(),
                links(DemoData.DOMAIN.id(), null, DemoData.ROBERT.id(), null, null, DemoData.DOMAIN.name()), true, false, false, null, "NONE", null, "priya", null, "ORG",
                "utility-bill-domain-lofts.pdf", pdf("Austin Energy statement", "Service address: 11500 Rock Rose Ave (common areas)", "Billing period: " + today.minusMonths(2).withDayOfMonth(1) + " to " + today.minusMonths(1).withDayOfMonth(1),
                "Usage: 6,420 kWh", "Amount due: $812.44", "Due: " + today.plusDays(9)), 12, new String[]{"utilities"}));
        put(org, new Upload("Travis County property tax – Barton Creek Townhomes", "BILL", "Property tax bill", "PROPERTY", DemoData.BARTON.id(),
                links(DemoData.BARTON.id(), null, DemoData.ROBERT.id(), null, null, DemoData.BARTON.name()), true, false, false, null, "NONE", null, "priya", null, "ORG",
                "property-tax-barton-creek.pdf", pdf("Travis Central Appraisal District – tax statement", "Property: 2700 S Lamar Blvd", "Tax year: " + today.getYear(), "Assessed value: $3,180,000", "Total due: $61,404.12", "Due: January 31, " + (today.getYear() + 1)), 20, new String[]{"tax"}));

        // Owners: W-9 and management agreement. Greenfield's W-9 is missing on purpose; its renewal is out for signature.
        for (DemoData.Owner o : List.of(DemoData.ROBERT, DemoData.JAMES, DemoData.LINDA, DemoData.GREENFIELD)) {
            boolean hasW9 = !o.equals(DemoData.GREENFIELD);
            String by = o.equals(DemoData.ROBERT) ? "robert" : o.equals(DemoData.GREENFIELD) ? "nathan" : "priya";
            String byScope = by.equals("priya") ? "ORG" : "OWNER";
            if (hasW9)
                put(org, new Upload("W-9 – " + o.name(), "TAX", "W-9", "OWNER", o.id(), links(null, null, o.id(), null, null, o.name()), true, false, false, null,
                        byScope.equals("OWNER") ? "ACCEPTED" : "NONE", null, by, null, byScope, "w9-" + slug(o.name()) + ".pdf",
                        pdf("Form W-9 (sample)", "Request for Taxpayer Identification Number and Certification", "Name: " + o.name(), "Federal tax classification: Individual / sole proprietor",
                                "TIN: ***-**-" + o.bankLast4(), "Signed: " + today.minusMonths(7)), 200, new String[]{"1099"}));
            if (!o.equals(DemoData.GREENFIELD))
                put(org, new Upload("Management agreement – " + o.name(), "CONTRACT", "Management agreement", "OWNER", o.id(), links(null, null, o.id(), null, null, o.name()), true, false, false,
                        today.plusMonths(5 + o.name().length() % 6), "NONE", null, "sarah", null, "ORG", "management-agreement-" + slug(o.name()) + ".pdf",
                        pdf("Property management agreement", "Owner: " + o.name(), "Manager: Summit Ridge Property Group LLC", "Management fee: " + o.managementFeePercent() + "% of collected rent",
                                "Owner approval required for repairs over $" + o.approvalLimit(), "Term: 12 months, renews automatically", "Signed by both parties"), 300, new String[]{"agreement"}));
        }
        UUID greenAgreement = put(org, new Upload("Management agreement 2027 – Greenfield Capital LLC", "CONTRACT", "Management agreement", "OWNER", DemoData.GREENFIELD.id(),
                links(null, null, DemoData.GREENFIELD.id(), null, null, DemoData.GREENFIELD.name()), true, false, false, null, "NONE", null, "sarah", null, "ORG", "management-agreement-greenfield-2027.pdf",
                pdf("Property management agreement – renewal", "Owner: Greenfield Capital LLC (Nathan Greene, Managing Partner)", "Properties: Capitol Hill Flats, Lake Union Residences",
                        "Management fee: 7% of collected rent", "Owner approval required for repairs over $1,500", "Term: January 1 to December 31, " + (today.getYear() + 1)), 3, new String[]{"agreement", "renewal"}));
        request(org, greenAgreement, "Please sign: 2027 management agreement", "Hi Nathan, here is the renewal we discussed. Same terms, fee stays at 7%.", "sarah",
                List.of(new String[]{"nathan", "OWNER", DemoData.GREENFIELD.id().toString(), "Owner"}, new String[]{"sarah", null, null, "Manager"}), true, 3, today.plusDays(9));

        // Vendors: certificate of insurance (date taken from the vendor record) and W-9. Emerald City Handyman has no W-9 yet.
        Map<String, String> insurance = new HashMap<>();
        if (vendors != null) for (JsonNode v : vendors) insurance.put(v.path("id").asText(), v.path("insurance_expires").asText(""));
        for (DemoData.Vendor v : DemoData.VENDORS) {
            String exp = insurance.getOrDefault(v.id().toString(), "");
            LocalDate e = exp.isBlank() ? today.plusDays(180) : LocalDate.parse(exp);
            boolean carlos = v.id().equals(DemoData.vendor("lonestar").id());
            put(org, new Upload("Certificate of insurance – " + v.name(), "INSURANCE", "Certificate of insurance", "VENDOR", v.id(), links(null, null, null, null, v.id(), v.name()), false, false, true, e,
                    carlos ? "ACCEPTED" : "NONE", null, carlos ? "carlos" : "david", null, carlos ? "VENDOR" : "ORG", "coi-" + slug(v.name()) + ".pdf",
                    pdf("Certificate of liability insurance (ACORD 25 sample)", "Insured: " + v.name(), "Certificate holder: Summit Ridge Property Group LLC",
                            "General liability: $1,000,000 each occurrence / $2,000,000 aggregate", "Workers' compensation: statutory limits", "Policy expires: " + e), 90 + v.jobsCompleted() % 60, new String[]{"coi"}));
            if (!v.id().equals(DemoData.vendor("emerald").id()))
                put(org, new Upload("W-9 – " + v.name(), "TAX", "W-9", "VENDOR", v.id(), links(null, null, null, null, v.id(), v.name()), false, false, true, null,
                        carlos ? "ACCEPTED" : "NONE", null, carlos ? "carlos" : "priya", null, carlos ? "VENDOR" : "ORG", "w9-" + slug(v.name()) + ".pdf",
                        pdf("Form W-9 (sample)", "Name: " + v.name(), "Federal tax classification: LLC (S corporation)", "EIN: **-***" + String.format("%04d", Math.abs(v.name().hashCode()) % 10000), "Signed: " + today.minusMonths(10)),
                        310, new String[]{"1099"}));
        }
        DemoData.Vendor lonestar = DemoData.vendor("lonestar");
        put(org, new Upload("Vendor agreement – Lone Star Plumbing", "CONTRACT", "Vendor agreement", "VENDOR", lonestar.id(), links(null, null, null, null, lonestar.id(), lonestar.name()), false, false, true,
                today.plusMonths(8), "NONE", null, "david", null, "ORG", "vendor-agreement-lone-star.pdf",
                pdf("Master service agreement", "Vendor: Lone Star Plumbing", "Rates: $95/hour, $65 trip charge, emergency 1.5x", "Response: 4 hours for urgent, 24 hours routine", "Insurance: $1M general liability required"), 150, new String[]{"agreement"}));

        // Residents: renters insurance, move-in inspection, a late-rent notice and a pet addendum out for signature.
        DemoData.Tenant michael = DemoData.tenant("michael"), ashley = DemoData.tenant("ashley"), sophia = DemoData.tenant("sophia");
        DemoData.Lease ml = DemoData.leaseForTenant(michael.id()).orElseThrow(), al = DemoData.leaseForTenant(ashley.id()).orElseThrow(), sl = DemoData.leaseForTenant(sophia.id()).orElseThrow();
        String mLabel = michael.name() + " – " + DemoData.DOMAIN.name() + " 101", aLabel = ashley.name() + " – " + DemoData.PACIFIC.name() + " 2B", sLabel = sophia.name() + " – " + DemoData.DOMAIN.name() + " 302";
        put(org, new Upload("Renters insurance – Michael Brown", "INSURANCE", "Renters insurance", "LEASE", ml.id(), links(ml.propertyId(), ml.unitId(), ml.ownerId(), michael.id(), null, mLabel), false, true, false,
                today.plusDays(21), "ACCEPTED", null, "michael", null, "TENANT", "renters-insurance-michael-brown.pdf",
                pdf("Renters insurance declarations", "Insured: Michael Brown", "Location: 11500 Rock Rose Ave, Unit 101, Austin TX", "Personal property: $30,000", "Liability: $100,000", "Interested party: Summit Ridge Property Group LLC", "Policy ends: " + today.plusDays(21)),
                330, new String[]{"insurance"}));
        put(org, new Upload("Renters insurance – Ashley Davis", "INSURANCE", "Renters insurance", "LEASE", al.id(), links(al.propertyId(), al.unitId(), al.ownerId(), ashley.id(), null, aLabel), false, true, false,
                today.minusDays(4), "ACCEPTED", null, "ashley", null, "TENANT", "renters-insurance-ashley-davis.pdf",
                pdf("Renters insurance declarations", "Insured: Ashley Davis", "Location: 3550 Front St, Unit 2B, San Diego CA", "Personal property: $25,000", "Liability: $300,000", "Policy ended: " + today.minusDays(4)),
                360, new String[]{"insurance"}));
        put(org, new Upload("Move-in inspection – Domain Lofts 101", "LEASE", "Move-in inspection", "LEASE", ml.id(), links(ml.propertyId(), ml.unitId(), ml.ownerId(), michael.id(), null, mLabel), true, true, false,
                null, "NONE", null, "jordan", null, "PROPERTY", "move-in-inspection-domain-101.pdf",
                pdf("Move-in condition report", "Unit: The Domain Lofts 101", "Resident: Michael Brown", "Living room: walls good, carpet clean, 1 nail hole by window",
                        "Kitchen: appliances working, small chip on counter edge", "Bathroom: good, grout slightly discolored", "Keys issued: 2 unit, 1 mailbox, 1 fob", "Signed by resident and agent"), 240, new String[]{}));
        put(org, new Upload("Late rent notice – Sophia Anderson", "NOTICE", "Late rent notice", "LEASE", sl.id(), links(sl.propertyId(), sl.unitId(), sl.ownerId(), sophia.id(), null, sLabel), false, true, false,
                null, "NONE", null, "marcus", null, "PROPERTY", "late-rent-notice-sophia-anderson.pdf",
                pdf("Notice of late rent", "To: Sophia Anderson, The Domain Lofts 302", "Rent of $2,150.00 due on the 1st has not been received.", "Late fee per lease: 5% after the 2-day grace period (Texas Property Code 92.019).",
                        "Please pay through the resident portal or contact the office to set up a payment plan."), 6, new String[]{"collections"}));
        UUID pet = put(org, new Upload("Pet addendum – Domain Lofts 101", "LEASE", "Pet addendum", "LEASE", ml.id(), links(ml.propertyId(), ml.unitId(), ml.ownerId(), michael.id(), null, mLabel), true, true, false,
                null, "NONE", null, "david", null, "ORG", "pet-addendum-domain-101.pdf",
                pdf("Pet addendum", "Resident: Michael Brown, The Domain Lofts 101", "Pet: one dog (beagle, 24 lb, \"Scout\")", "Pet deposit: $300 refundable; pet rent: $35/month",
                        "Resident is responsible for damage and waste removal; dog must be leashed in common areas.", "Assistance animals are not pets and are never charged pet fees (Fair Housing Act)."), 1, new String[]{"addendum"}));
        request(org, pet, "Please sign: pet addendum for Scout", "Hi Michael, here's the pet addendum for Scout. Sign when you're ready and David will countersign.", "david",
                List.of(new String[]{"michael", "TENANT", michael.id().toString(), "Resident"}, new String[]{"david", null, null, "Property manager"}), true, 1, today.plusDays(13));

        // Applicant files, by application stage.
        for (JsonNode a : apps) {
            UUID aid = UUID.fromString(a.path("id").asText());
            String name = a.path("applicant_name").asText(), status = a.path("status").asText(), label = a.path("label").asText(name);
            Lookups.Links l = links(uuid(a, "property_id"), uuid(a, "unit_id"), uuid(a, "owner_id"), null, null, label);
            String review = List.of("SUBMITTED", "DOCS_PENDING").contains(status) ? "PENDING" : "ACCEPTED";
            put(org, new Upload("Photo ID – " + name, "IDENTITY", "Photo ID", "APPLICATION", aid, l, false, false, false, null, review, null, null, name, "APPLICANT",
                    "photo-id-" + slug(name) + ".pdf", pdf("Driver license (sample image omitted)", "Name: " + name, "Class C, expires 2029", "Verified against application name"), 4, new String[]{}));
            if ("DOCS_PENDING".equals(status)) {
                put(org, new Upload("Bank statement – " + name, "APPLICATION", "Bank statement", "APPLICATION", aid, l, false, false, false, null, "REJECTED",
                        "Statement is older than 60 days. Please upload one from the last two months.", null, name, "APPLICANT", "bank-statement-" + slug(name) + ".pdf",
                        pdf("Checking account statement", "Account holder: " + name, "Period: " + today.minusMonths(5).withDayOfMonth(1) + " to " + today.minusMonths(4).withDayOfMonth(1), "Ending balance: $4,812.30"), 3, new String[]{}));
            } else if (!"SUBMITTED".equals(status)) {
                for (int k = 1; k <= 2; k++)
                    put(org, new Upload("Pay stub " + k + " – " + name, "APPLICATION", "Proof of income", "APPLICATION", aid, l, false, false, false, null, review, null, null, name, "APPLICANT",
                            "pay-stub-" + k + "-" + slug(name) + ".pdf", pdf("Earnings statement", "Employee: " + name, "Employer: Acme Corp", "Pay date: " + today.minusDays(14L * k), "Gross pay: $" + (3200 + k * 15) + ".00", "Year to date: $" + (58000 + k * 3200) + ".00"), 4, new String[]{}));
            }
        }

        // Work orders: photo from the resident; vendor invoice once the job is done.
        for (JsonNode w : wos) {
            UUID wid = UUID.fromString(w.path("id").asText());
            String title = w.path("title").asText(), status = w.path("status").asText();
            Lookups.Links l = links(uuid(w, "property_id"), uuid(w, "unit_id"), uuid(w, "owner_id"), uuid(w, "tenant_id"), uuid(w, "vendor_id"), w.path("label").asText(title));
            UUID tenant = uuid(w, "tenant_id");
            String tenantKey = tenant == null ? null : DemoData.users().stream().filter(u -> tenant.equals(u.partyId())).map(u -> u.id()).findFirst().map(x -> x.equals(user("michael")) ? "michael" : x.equals(user("ashley")) ? "ashley" : null).orElse(null);
            String tenantName = tenant == null ? "Resident" : DemoData.TENANTS.stream().filter(t -> t.id().equals(tenant)).map(DemoData.Tenant::name).findFirst().orElse("Resident");
            put(org, new Upload("Photo – " + title, "WORK_ORDER", "Photo", "WORK_ORDER", wid, l, true, tenant != null, l.vendorId() != null, null, "NONE", null, tenantKey, tenantKey == null ? tenantName : null,
                    "TENANT", "photo-" + slug(title) + ".png", photo(title), 2, new String[]{}));
            if (List.of("COMPLETED", "CLOSED", "VERIFIED").contains(status) && l.vendorId() != null) {
                boolean carlos = l.vendorId().equals(lonestar.id());
                String vendorName = DemoData.VENDORS.stream().filter(v -> v.id().equals(l.vendorId())).map(DemoData.Vendor::name).findFirst().orElse("Vendor");
                put(org, new Upload("Invoice – " + title, "BILL", "Vendor invoice", "WORK_ORDER", wid, l, true, false, true, null, carlos ? "ACCEPTED" : "NONE", null, carlos ? "carlos" : "david", null,
                        carlos ? "VENDOR" : "ORG", "invoice-" + slug(title) + ".pdf",
                        pdf("Invoice from " + vendorName, "Job: " + title, "Location: " + w.path("label").asText(), "Labor: 2.0 hours at $95.00", "Parts: $" + (40 + title.length()) + ".00",
                                "Total: $" + (230 + title.length()) + ".00", "Terms: net 15"), 1, new String[]{}));
            }
        }
    }

    private void request(UUID org, UUID docId, String title, String message, String byKey, List<String[]> signers, boolean sequential, int daysAgo, LocalDate due) {
        Map<String, Object> d = db.one("Document", "select sha256, property_id from document.document where id = ?", docId);
        UUID rid = UUID.randomUUID();
        db.update("insert into document.signature_request (id, org_id, document_id, property_id, title, message, sequential, document_sha256, due_on, created_by, created_by_name, created_at) values (?,?,?,?,?,?,?,?,?,?,?, now() - make_interval(days => ?))",
                rid, org, docId, d.get("property_id"), title, message, sequential, d.get("sha256"), due, user(byKey), userName(byKey), daysAgo);
        int order = 1;
        for (String[] s : signers)
            db.update("insert into document.signature_signer (id, request_id, document_id, org_id, signer_order, user_id, party_type, party_id, name, role_label, viewed_at) values (?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), rid, docId, org, order++, user(s[0]), s[1], s[2] == null ? null : UUID.fromString(s[2]), userName(s[0]), s[3], null);
        db.update("update document.document set signature_status = 'PENDING' where id = ?", docId);
        db.update("insert into platform.audit_event (org_id, entity_type, entity_id, action, to_state, actor_id, actor_name, details, created_at) values (?, 'Document', ?, 'SIGNATURE_REQUESTED', 'PENDING', ?, ?, '{}'::jsonb, now() - make_interval(days => ?))",
                org, docId, user(byKey), userName(byKey), daysAgo);
    }

    private static UUID uuid(JsonNode n, String f) {
        String s = n.path(f).asText("");
        return s.isBlank() || "null".equals(s) ? null : UUID.fromString(s);
    }

    private static String slug(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static byte[] pdf(String title, String... lines) {
        Pdf p = new Pdf("PropertyOS sample document");
        p.title(title).space();
        for (String l : lines) p.text(l);
        p.space().space().text(SAMPLE);
        return p.bytes();
    }

    /** A simple illustrated placeholder photo (no real images of people or places). */
    private static byte[] photo(String caption) {
        try {
            BufferedImage img = new BufferedImage(960, 640, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = Math.abs(caption.hashCode());
            g.setPaint(new GradientPaint(0, 0, new Color(60 + h % 60, 80 + h % 50, 100 + h % 40), 960, 640, new Color(20 + h % 30, 30 + h % 40, 50 + h % 30)));
            g.fillRect(0, 0, 960, 640);
            g.setColor(new Color(255, 255, 255, 40));
            for (int k = 0; k < 9; k++) g.fillRoundRect(80 + k * 90, 380 - (k % 3) * 60, 70, 180 + (k % 3) * 60, 12, 12);
            g.setColor(new Color(255, 255, 255, 230));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 34));
            g.drawString(caption.length() > 44 ? caption.substring(0, 44) + "..." : caption, 50, 90);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 22));
            g.drawString("Sample photo for the PropertyOS demo", 50, 130);
            g.dispose();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
        }
    }
}
