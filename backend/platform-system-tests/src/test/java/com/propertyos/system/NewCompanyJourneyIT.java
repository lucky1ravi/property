package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The demo story from nothing: the platform operator onboards a brand-new company, its administrator signs in for the
 * first time and sets up owners, properties, units and vendors, lists a unit, signs a resident, bills and pays,
 * handles a repair, and gives owners, residents and vendors their own sign-ins. Nothing is pre-seeded for this company,
 * so anything the product needs but never creates for a new company shows up here as a gap.
 */
class NewCompanyJourneyIT {
    private final ApiClient identity = Flow.identity();
    private final ApiClient leasing = Flow.leasing();
    private final ApiClient billing = Flow.billing();
    private final ApiClient maintenance = Flow.maintenance();
    private final ApiClient workflow = Flow.workflow();
    private final List<String> gaps = new ArrayList<>();
    private final Map<String, String> passwords = new java.util.HashMap<>();

    private void check(boolean condition, String what) {
        if (!condition) gaps.add(what);
    }

    private Reply step(Reply r, String what) {
        if (r.status() < 200 || r.status() >= 300) gaps.add(what + " -> " + r.status() + " " + r.body());
        return r;
    }

    private String firstSignIn(String email, String temp) {
        String pw = "Journey-" + UUID.randomUUID().toString().substring(0, 8) + "-pw";
        Reply r = identity.post(null, "/api/identity/public/change-password", Map.of("email", email, "currentPassword", temp, "newPassword", pw));
        step(r, "first sign-in for " + email);
        passwords.put(email, pw);
        return r.body().path("token").asText();
    }

    @Test
    void aNewCompanyCanBeSetUpFromScratchAndRun() throws Exception {
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        String platform = Flow.token("PLATFORM_ADMIN");
        String summit = Flow.token("ORG_ADMIN");

        // 1. Onboarding and first sign-in.
        String adminEmail = "owner." + sfx + "@journey.test";
        Reply onboarded = Flow.ok(identity.post(platform, "/api/identity/platform/orgs", Flow.map("name", "Journey Realty " + sfx, "state", "TX", "adminName", "Jo Admin",
                "adminEmail", adminEmail, "plan", "ENTERPRISE")), "onboard company");
        String admin = firstSignIn(adminEmail, onboarded.body().path("temporaryPassword").asText());
        JsonNode me = Flow.ok(identity.get(admin, "/api/identity/me"), "me").body();
        check(("Journey Realty " + sfx).equals(me.path("branding").path("companyName").asText()), "company shows under its own name");

        // 2. A new company starts empty and sees nothing of anyone else's.
        for (String path : List.of("/api/leasing/properties", "/api/leasing/units", "/api/leasing/owners", "/api/leasing/listings", "/api/leasing/leases", "/api/leasing/applications")) {
            Reply r = leasing.get(admin, path);
            step(r, "empty " + path);
            check(Flow.rows(r).isEmpty(), "new company starts empty: " + path + " had " + Flow.rows(r).size());
        }
        check(Flow.rows(maintenance.get(admin, "/api/maintenance/vendors")).isEmpty(), "no vendors inherited");
        check(Flow.rows(maintenance.get(admin, "/api/maintenance/work-orders")).isEmpty(), "no work orders inherited");
        check(Flow.rows(billing.get(admin, "/api/billing/invoices")).isEmpty(), "no invoices inherited");

        // 3. Automation rules arrive with the company (asynchronously).
        JsonNode rules = Flow.rows(workflow.get(admin, "/api/workflow/rules"));
        for (int i = 0; i < 30 && rules.isEmpty(); i++) {
            Thread.sleep(1000);
            rules = Flow.rows(workflow.get(admin, "/api/workflow/rules"));
        }
        check(!rules.isEmpty(), "automation rules are created for a new company");
        step(workflow.get(admin, "/api/workflow/workflows"), "workflow definitions");

        // 4. Portfolio set-up: owner, property, units, edits.
        Reply owner = step(leasing.post(admin, "/api/leasing/owners", Flow.map("name", "Maple Holdings " + sfx, "contact", "Olivia Owner", "email", "olivia." + sfx + "@journey.test",
                "phone", "5125550101", "managementFeePct", 7.5, "approvalLimit", 750)), "create owner");
        String ownerId = owner.body().path("id").asText();
        check(leasing.post(admin, "/api/leasing/owners", Map.of("contact", "No name")).status() == 400, "owner needs a name");
        step(leasing.put(admin, "/api/leasing/owners/" + ownerId, Map.of("phone", "5125550199")), "edit owner");
        Reply property = step(leasing.post(admin, "/api/leasing/properties", Flow.map("ownerId", ownerId, "name", "Journey Court", "street", "10 Main St", "city", "Austin", "state", "TX",
                "zip", "78701", "type", "MULTIFAMILY", "yearBuilt", 2005)), "create property");
        String propertyId = property.body().path("id").asText();
        step(leasing.put(admin, "/api/leasing/properties/" + propertyId, Map.of("name", "Journey Court Apartments")), "edit property");
        Reply u1 = step(leasing.post(admin, "/api/leasing/properties/" + propertyId + "/units", Map.of("number", "1A", "beds", 2, "baths", "1.5", "sqft", 900, "marketRent", 1800)), "create unit 1A");
        Reply u2 = step(leasing.post(admin, "/api/leasing/properties/" + propertyId + "/units", Map.of("number", "2B", "beds", 1, "baths", "1", "sqft", 650, "marketRent", 1400)), "create unit 2B");
        String unit1 = u1.body().path("id").asText();
        step(leasing.put(admin, "/api/leasing/units/" + unit1, Map.of("marketRent", 1850, "sqft", 920)), "edit unit");
        check(leasing.put(admin, "/api/leasing/units/" + unit1, Map.of("marketRent", -1)).status() == 400, "unit rent must be positive");
        check(Flow.rows(leasing.get(admin, "/api/leasing/properties")).size() == 1, "property is listed");
        check(Flow.rows(leasing.get(admin, "/api/leasing/units")).size() == 2, "both units are listed");
        check(u2.status() == 200, "second unit created");

        // 5. Vendor directory.
        Reply vendor = step(maintenance.post(admin, "/api/maintenance/vendors", Flow.map("name", "Austin Plumbing " + sfx, "contact", "Pete Pipes", "phone", "5125550123",
                "email", "pete." + sfx + "@journey.test", "trades", List.of("PLUMBING", "GENERAL"), "cities", List.of("Austin"), "insuranceExpires", LocalDate.now().plusMonths(8).toString())), "create vendor");
        String vendorId = vendor.body().path("id").asText();
        check(maintenance.post(admin, "/api/maintenance/vendors", Map.of("name", "No trades")).status() == 400, "vendor needs a trade");
        step(maintenance.put(admin, "/api/maintenance/vendors/" + vendorId, Map.of("phone", "5125550124")), "edit vendor");

        // 6. List a unit and publish it.
        Reply listing = step(leasing.post(admin, "/api/leasing/listings", Map.of("unitId", unit1)), "create listing");
        String listingId = listing.body().path("id").asText();
        step(leasing.post(admin, "/api/leasing/listings/" + listingId + "/ai-assist", Map.of()), "listing AI help");
        step(leasing.post(admin, "/api/leasing/listings/" + listingId + "/actions/SUBMIT", Map.of()), "submit listing");
        JsonNode live = leasing.get(admin, "/api/leasing/listings/" + listingId).body();
        for (int i = 0; i < 40 && "AI_REVIEW".equals(live.path("status").asText()); i++) {
            Thread.sleep(1000); // the automatic review runs in the background
            live = leasing.get(admin, "/api/leasing/listings/" + listingId).body();
        }
        check("LIVE".equals(live.path("status").asText()), "listing can be published (status " + live.path("status").asText() + ")");
        boolean public_ = false;
        for (JsonNode l : leasing.get(null, "/api/leasing/public/listings").body()) if (listingId.equals(l.path("id").asText())) public_ = true;
        check(public_, "published listing appears on the public site");

        // 7. A prospect applies, is screened, approved and signs a lease.
        String appEmail = "resident." + sfx + "@journey.test";
        Reply applied = step(leasing.post(null, "/api/leasing/public/listings/" + listingId + "/applications", Flow.map("firstName", "Rae", "lastName", "Resident", "email", appEmail,
                "phone", "5125550177", "consent", true, "monthlyIncome", 9000, "employer", "Acme", "monthsEmployed", 30, "occupants", 2, "moveInDate", LocalDate.now().plusDays(14).toString())), "apply");
        String appId = applied.body().path("id").asText();
        String status = "";
        for (int i = 0; i < 30; i++) {
            status = leasing.get(admin, "/api/leasing/applications/" + appId).body().path("status").asText();
            if (List.of("SCREENED", "APPROVED", "OWNER_REVIEW").contains(status)) break;
            Thread.sleep(1000);
        }
        check(List.of("SCREENED", "APPROVED", "OWNER_REVIEW").contains(status), "application is screened automatically (status " + status + ")");
        for (String action : List.of("APPROVE", "SEND_TO_OWNER", "APPROVE")) leasing.post(admin, "/api/leasing/applications/" + appId + "/actions/" + action, Map.of("reason", "ok"));
        Reply lease = step(leasing.post(admin, "/api/leasing/applications/" + appId + "/lease", Map.of("termMonths", 12, "startDate", LocalDate.now().plusDays(14).toString())), "create lease");
        String leaseId = lease.body().path("id").asText();
        step(leasing.post(admin, "/api/leasing/leases/" + leaseId + "/actions/SEND", Map.of()), "send lease");
        step(leasing.post(admin, "/api/leasing/leases/" + leaseId + "/actions/TENANT_SIGN", Map.of("signature", "Rae Resident")), "tenant signs");
        step(leasing.post(admin, "/api/leasing/leases/" + leaseId + "/actions/COUNTERSIGN", Map.of()), "countersign");
        JsonNode active = leasing.get(admin, "/api/leasing/leases/" + leaseId).body();
        check("ACTIVE".equals(active.path("status").asText()), "lease is active (status " + active.path("status").asText() + ")");
        String tenantId = active.path("tenant_id").asText();

        // 8. Billing: rent is invoiced and can be paid.
        step(billing.post(admin, "/api/billing/run-cycle", Map.of()), "run billing cycle");
        JsonNode invoices = Flow.rows(billing.get(admin, "/api/billing/invoices"));
        check(!invoices.isEmpty(), "billing produces invoices for the new lease");
        if (!invoices.isEmpty()) {
            step(billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenantId, "amount", 100, "method", "ACH")), "record a payment");
        }
        step(billing.get(admin, "/api/billing/summary"), "billing summary");
        step(billing.get(admin, "/api/billing/statements"), "owner statements list");

        // 9. A repair request goes through to completion with the new vendor.
        Reply wo = step(maintenance.post(admin, "/api/maintenance/work-orders", Flow.map("propertyId", propertyId, "unitId", unit1, "title", "Kitchen sink leaking",
                "description", "Water under the sink and the cabinet floor is wet.", "permissionToEnter", true)), "create work order");
        String woId = wo.body().path("id").asText();
        Flow.Soft woWalk = new Flow.Soft();
        Flow.walk(woWalk, maintenance, admin, "/api/maintenance/work-orders", woId, 0, 8, Map.of("vendorId", vendorId));
        String woStatus = maintenance.get(admin, "/api/maintenance/work-orders/" + woId).body().path("status").asText();
        check(!"NEW".equals(woStatus) && !woStatus.isBlank(), "work order moves along (status " + woStatus + ")");

        step(maintenance.post(admin, "/api/maintenance/recurring", Map.of("propertyId", propertyId, "title", "Quarterly filter change", "category", "HVAC", "everyMonths", 3)), "recurring task");
        check(Flow.rows(maintenance.get(admin, "/api/maintenance/recurring")).size() == 1, "recurring task is listed");
        step(billing.post(admin, "/api/billing/expenses", Map.of("propertyId", propertyId, "ownerId", ownerId, "description", "Gutter cleaning", "category", "REPAIRS", "amount", 180)), "record expense");

        // 9b. Archiving: nothing in use can be archived, nothing is lost, and everything can be brought back.
        check(leasing.delete(admin, "/api/leasing/units/" + unit1).status() == 409, "an occupied unit can't be archived");
        check(leasing.delete(admin, "/api/leasing/properties/" + propertyId).status() == 409, "a property with an occupied unit can't be archived");
        check(leasing.delete(admin, "/api/leasing/owners/" + ownerId).status() == 409, "an owner with properties can't be archived");
        Reply owner2 = step(leasing.post(admin, "/api/leasing/owners", Map.of("name", "Spare Owner " + sfx)), "second owner");
        String owner2Id = owner2.body().path("id").asText();
        String prop2 = step(leasing.post(admin, "/api/leasing/properties", Flow.map("ownerId", owner2Id, "name", "Spare House", "street", "5 Elm St", "city", "Austin", "state", "TX", "zip", "78702")), "second property").body().path("id").asText();
        String unit2 = step(leasing.post(admin, "/api/leasing/properties/" + prop2 + "/units", Map.of("number", "A", "marketRent", 1300)), "second unit").body().path("id").asText();
        check(leasing.delete(admin, "/api/leasing/owners/" + owner2Id).status() == 409, "an owner can't be archived while a property is active");
        String draft = step(leasing.post(admin, "/api/leasing/listings", Map.of("unitId", unit2)), "draft listing").body().path("id").asText();
        check(leasing.delete(admin, "/api/leasing/units/" + unit2).status() == 409, "a unit with an open listing can't be archived");
        check(leasing.delete(admin, "/api/leasing/listings/" + listingId).status() == 409, "a published listing can't be deleted");
        check(leasing.delete(admin, "/api/leasing/listings/" + draft).status() == 200 || leasing.get(admin, "/api/leasing/listings/" + draft).status() == 404, "a draft listing can be deleted");
        check(leasing.get(admin, "/api/leasing/listings/" + draft).status() == 404, "the deleted draft is gone");
        check(leasing.delete(admin, "/api/leasing/units/" + unit2).status() == 200, "a vacant unit can be archived");
        check(Flow.rows(leasing.get(admin, "/api/leasing/units")).size() == 2, "the archived unit is hidden from lists");
        check(leasing.post(admin, "/api/leasing/properties/" + prop2 + "/units", Map.of("number", "B", "marketRent", 1300)).status() == 200, "a property still takes units");
        check(leasing.delete(admin, "/api/leasing/properties/" + prop2).status() == 200, "a property with no occupied units can be archived");
        check(Flow.rows(leasing.get(admin, "/api/leasing/properties")).size() == 1, "the archived property is hidden from lists");
        check(leasing.post(admin, "/api/leasing/properties/" + prop2 + "/units", Map.of("number", "C", "marketRent", 1300)).status() == 409, "no units can be added to an archived property");
        check(leasing.delete(admin, "/api/leasing/owners/" + owner2Id).status() == 200, "an owner with no active properties can be archived");
        check(Flow.rows(leasing.get(admin, "/api/leasing/owners")).size() == 1, "the archived owner is hidden from lists");
        check(leasing.post(admin, "/api/leasing/properties", Flow.map("ownerId", owner2Id, "name", "X", "street", "1", "city", "A", "state", "TX", "zip", "78701")).status() == 409, "no property can be added to an archived owner");
        check(leasing.post(admin, "/api/leasing/properties/" + prop2 + "/restore", Map.of()).status() == 409, "a property can't return before its owner");
        check(leasing.post(admin, "/api/leasing/owners/" + owner2Id + "/restore", Map.of()).status() == 200, "restore owner");
        check(leasing.post(admin, "/api/leasing/properties/" + prop2 + "/restore", Map.of()).status() == 200, "restore property");
        check(Flow.rows(leasing.get(admin, "/api/leasing/properties")).size() == 2 && Flow.rows(leasing.get(admin, "/api/leasing/owners")).size() == 2, "restored records are back");
        check(leasing.delete(admin, "/api/leasing/units/" + UUID.randomUUID()).status() == 404, "unknown unit");

        // Vendors are archived, not deleted; a vendor with open jobs stays.
        String vendor2 = step(maintenance.post(admin, "/api/maintenance/vendors", Map.of("name", "Spare Vendor " + sfx, "trades", List.of("GENERAL"))), "second vendor").body().path("id").asText();
        check(maintenance.put(admin, "/api/maintenance/vendors/" + vendor2, Map.of("active", false)).status() == 200, "a vendor with no open jobs can be archived");
        boolean archivedVendor = false;
        for (JsonNode v : Flow.rows(maintenance.get(admin, "/api/maintenance/vendors"))) if (vendor2.equals(v.path("id").asText()) && !v.path("active").asBoolean(true)) archivedVendor = true;
        check(archivedVendor, "the archived vendor is marked inactive");
        check(maintenance.put(admin, "/api/maintenance/vendors/" + vendor2, Map.of("active", true)).status() == 200, "a vendor can be reactivated");

        // Owners have their own permission: a broker can see owners but not add or archive them.
        String brokerRole = null;
        for (JsonNode r : Flow.rows(identity.get(admin, "/api/identity/roles"))) if ("BROKER".equals(r.path("role_key").asText())) brokerRole = r.path("id").asText();
        if (brokerRole != null) {
            Reply b = step(identity.post(admin, "/api/identity/users", Flow.map("email", "broker." + sfx + "@journey.test", "name", "Bea Broker", "roleIds", List.of(brokerRole), "propertyIds", List.of(propertyId))), "broker user");
            String brokerTok = firstSignIn("broker." + sfx + "@journey.test", b.body().path("temporaryPassword").asText());
            check(leasing.get(brokerTok, "/api/leasing/owners").status() == 200, "a broker can see owners");
            check(leasing.post(brokerTok, "/api/leasing/owners", Map.of("name", "Nope")).status() == 403, "a broker can't add owners");
            check(leasing.put(brokerTok, "/api/leasing/owners/" + ownerId, Map.of("phone", "1")).status() == 403, "a broker can't edit owners");
            check(leasing.delete(brokerTok, "/api/leasing/properties/" + prop2).status() == 403, "a broker can't archive properties");
            check(leasing.post(brokerTok, "/api/leasing/properties", Map.of("ownerId", ownerId, "name", "N")).status() == 403, "a broker can't add properties");
            check(maintenance.post(brokerTok, "/api/maintenance/vendors", Map.of("name", "N", "trades", List.of("GENERAL"))).status() == 403, "a broker can't add vendors");
        }

        // 10. Portal sign-ins for the owner, the resident and the vendor.
        JsonNode roles = Flow.rows(identity.get(admin, "/api/identity/roles"));
        String ownerRole = null, tenantRole = null, vendorRole = null;
        for (JsonNode r : roles) {
            switch (r.path("role_key").asText()) {
                case "OWNER" -> ownerRole = r.path("id").asText();
                case "TENANT" -> tenantRole = r.path("id").asText();
                case "VENDOR" -> vendorRole = r.path("id").asText();
                default -> { }
            }
        }
        check(ownerRole != null && tenantRole != null && vendorRole != null, "owner, resident and vendor roles exist");
        JsonNode residents = Flow.rows(leasing.get(admin, "/api/leasing/residents"));
        check(residents.size() == 1, "resident list shows the new resident (" + residents.size() + ")");
        if (ownerRole != null && tenantRole != null && vendorRole != null && !residents.isEmpty()) {
            Reply ownerUser = step(identity.post(admin, "/api/identity/users", Flow.map("email", "olivia." + sfx + "@journey.test", "name", "Olivia Owner", "roleIds", List.of(ownerRole),
                    "partyType", "OWNER", "partyId", ownerId)), "owner sign-in");
            Reply tenantUser = step(identity.post(admin, "/api/identity/users", Flow.map("email", appEmail, "name", "Rae Resident", "roleIds", List.of(tenantRole),
                    "partyType", "TENANT", "partyId", residents.get(0).path("id").asText())), "resident sign-in");
            Reply vendorUser = step(identity.post(admin, "/api/identity/users", Flow.map("email", "pete." + sfx + "@journey.test", "name", "Pete Pipes", "roleIds", List.of(vendorRole),
                    "partyType", "VENDOR", "partyId", vendorId)), "vendor sign-in");
            String ownerTok = firstSignIn("olivia." + sfx + "@journey.test", ownerUser.body().path("temporaryPassword").asText());
            String tenantTok = firstSignIn(appEmail, tenantUser.body().path("temporaryPassword").asText());
            String vendorTok = firstSignIn("pete." + sfx + "@journey.test", vendorUser.body().path("temporaryPassword").asText());
            check(Flow.rows(leasing.get(ownerTok, "/api/leasing/properties")).size() == 1, "owner sees their property");
            check(leaseId.equals(leasing.get(tenantTok, "/api/leasing/leases/mine").body().path("id").asText()), "resident sees their own lease");
            step(billing.get(tenantTok, "/api/billing/my"), "resident billing view");
            check(Flow.rows(maintenance.get(vendorTok, "/api/maintenance/work-orders")).size() >= 1, "vendor sees the job assigned to them");
            check(Flow.rows(leasing.get(vendorTok, "/api/leasing/properties")).isEmpty() || leasing.get(vendorTok, "/api/leasing/properties").status() == 403, "vendor does not see the portfolio");
        }

        // 11. Nothing leaks either way between companies.
        for (JsonNode p : Flow.rows(leasing.get(summit, "/api/leasing/properties"))) check(!propertyId.equals(p.path("id").asText()), "the other company must not see the new property");

        // 12. Suspending the company turns away everyone in it, hides its homes, and is fully reversible.
        String orgId = onboarded.body().path("orgId").asText();
        String orgPath = "/api/identity/platform/orgs/" + orgId;
        check(identity.put(platform, orgPath, Map.of("status", "SUSPENDED")).status() == 400, "suspending needs a reason");
        check(identity.put(platform, orgPath, Map.of("status", "PAUSED")).status() == 400, "status must be ACTIVE or SUSPENDED");
        check(identity.put(platform, "/api/identity/platform/orgs/" + com.propertyos.common.demo.DemoData.PLATFORM.id(), Map.of("status", "SUSPENDED", "reason", "x")).status() == 400, "the platform itself can't be suspended");
        step(identity.put(platform, orgPath, Map.of("status", "SUSPENDED", "reason", "Unpaid invoice")), "suspend company");
        JsonNode suspendedDetails = identity.get(platform, orgPath).body();
        check("Unpaid invoice".equals(suspendedDetails.path("status_reason").asText()), "the reason is kept on the company");
        Reply blockedLogin = identity.post(null, "/api/identity/public/login", Map.of("email", adminEmail, "password", passwords.get(adminEmail)));
        check(blockedLogin.status() == 403 && blockedLogin.body().path("detail").asText().contains("suspended"), "nobody can sign in while suspended");
        boolean cutOff = false;
        for (int i = 0; i < 40 && !cutOff; i++) { // other services learn of it within seconds
            Reply r = leasing.get(admin, "/api/leasing/properties");
            cutOff = r.status() == 403 && "ORG_SUSPENDED".equals(r.body().path("code").asText());
            if (!cutOff) Thread.sleep(1000);
        }
        check(cutOff, "people already signed in are turned away");
        check(maintenance.get(admin, "/api/maintenance/vendors").status() == 403, "every service turns them away");
        boolean stillPublic = false;
        for (JsonNode l : leasing.get(null, "/api/leasing/public/listings").body()) if (listingId.equals(l.path("id").asText())) stillPublic = true;
        check(!stillPublic, "a suspended company's homes are hidden from the public site");
        check(leasing.get(null, "/api/leasing/public/listings/" + listingId).status() == 404, "its listing page is not found");
        check(identity.get(platform, orgPath).status() == 200, "the operator can still see the company");

        step(identity.put(platform, orgPath, Map.of("status", "ACTIVE")), "reactivate company");
        boolean back = false;
        for (int i = 0; i < 40 && !back; i++) {
            back = leasing.get(admin, "/api/leasing/properties").status() == 200;
            if (!back) Thread.sleep(1000);
        }
        check(back, "reactivating restores access with the same session");
        check(identity.post(null, "/api/identity/public/login", Map.of("email", adminEmail, "password", passwords.get(adminEmail))).status() == 200, "sign-in works again");
        boolean publicAgain = false;
        for (JsonNode l : leasing.get(null, "/api/leasing/public/listings").body()) if (listingId.equals(l.path("id").asText())) publicAgain = true;
        check(publicAgain, "its homes are public again");

        assertTrue(gaps.isEmpty(), "Gaps for a company set up from scratch:\n - " + String.join("\n - ", gaps));
    }
}
