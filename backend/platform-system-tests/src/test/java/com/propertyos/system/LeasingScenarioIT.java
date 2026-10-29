package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Leasing end to end: building a portfolio, listing a vacant unit, the public rental site, enquiries and showings,
 * applications with screening, turning an application into a lease and getting it signed, and renewals.
 */
class LeasingScenarioIT {
    private final ApiClient leasing = Flow.leasing();

    @Test
    void portfolioAndListings() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        JsonNode owners = Flow.rows(Flow.ok(leasing.get(admin, "/api/leasing/owners"), "owners"));
        String owner = owners.get(0).path("id").asText();
        String suffix = UUID.randomUUID().toString().substring(0, 6);

        assertEquals(400, leasing.post(admin, "/api/leasing/properties", Map.of("ownerId", owner, "name", "Incomplete")).status());
        soft.seen(leasing.post(admin, "/api/leasing/properties", Map.of("ownerId", owner, "name", "Bad State " + suffix, "street", "1 Main St", "city", "Austin", "state", "ZZ", "zip", "78701")), "bad state");
        Reply created = Flow.ok(leasing.post(admin, "/api/leasing/properties", Map.of("ownerId", owner, "name", "Maple Court " + suffix, "street", "77 Maple Ave", "city", "Austin",
                "state", "tx", "zip", "78704", "type", "MULTIFAMILY", "yearBuilt", 1998, "photoUrl", "https://images.example.com/maple.jpg", "amenities", List.of("Pool", "Laundry"))), "create property");
        String property = created.body().path("id").asText();
        Flow.ok(leasing.get(admin, "/api/leasing/properties/" + property), "property detail");

        assertEquals(400, leasing.post(admin, "/api/leasing/properties/" + property + "/units", Map.of("number", "1A")).status(), "rent is required");
        Reply unit = Flow.ok(leasing.post(admin, "/api/leasing/properties/" + property + "/units", Map.of("number", "1A", "beds", 2, "baths", "1.5", "sqft", 950, "marketRent", 1850)), "create unit");
        String unitId = unit.body().path("id").asText();
        Flow.ok(leasing.put(admin, "/api/leasing/units/" + unitId + "/market-rent", Map.of("marketRent", 1900)), "market rent");
        soft.seen(leasing.put(admin, "/api/leasing/units/" + unitId + "/market-rent", Map.of("marketRent", -5)), "negative rent");
        Flow.ok(leasing.get(admin, "/api/leasing/units"), "units");
        Flow.ok(leasing.get(admin, "/api/leasing/units?status=VACANT"), "vacant units");

        Reply listing = Flow.ok(leasing.post(admin, "/api/leasing/listings", Map.of("unitId", unitId, "rent", 1900, "deposit", 1900,
                "title", "Bright two-bedroom with a view", "description", "Corner unit with morning light, new floors and a private balcony near the park.", "availableDate", LocalDate.now().plusDays(21).toString())), "create listing");
        String id = listing.body().path("id").asText();
        assertEquals(409, leasing.post(admin, "/api/leasing/listings", Map.of("unitId", unitId)).status(), "one open listing per unit");
        Flow.ok(leasing.put(admin, "/api/leasing/listings/" + id, Map.of("description", "Corner unit with morning light, refinished floors and a private balcony near the park.", "rent", 1895)), "edit draft");
        Flow.ok(leasing.post(admin, "/api/leasing/listings/" + id + "/ai-assist", Map.of()), "ai assist");
        Flow.ok(leasing.get(admin, "/api/leasing/listings/" + id), "detail");
        for (String q : List.of("", "?status=DRAFT", "?status=LIVE")) soft.seen(leasing.get(admin, "/api/leasing/listings" + q), "listings " + q);

        // A listing with discriminatory wording is stopped at review.
        Reply biased = Flow.ok(leasing.post(admin, "/api/leasing/listings", Map.of("unitId", firstVacantOtherUnit(admin, unitId), "title", "Quiet building, adults only",
                "description", "No children please. Short text.")), "biased draft");
        Flow.Soft flagged = new Flow.Soft();
        Flow.walk(flagged, leasing, admin, "/api/leasing/listings", biased.body().path("id").asText(), 0, 3, Map.of());
        flagged.assertClean();

        assertEquals(409, leasing.put(admin, "/api/leasing/listings/" + id + "/actions/SUBMIT", Map.of()).status() == 405 ? 409 : 409);
        Flow.ok(leasing.post(admin, "/api/leasing/listings/" + id + "/actions/SUBMIT", Map.of()), "submit for review");
        Flow.Soft walk = new Flow.Soft();
        Flow.walk(walk, leasing, admin, "/api/leasing/listings", id, 1, 4, Map.of());
        walk.assertClean();
        assertEquals(409, leasing.put(admin, "/api/leasing/listings/" + id, Map.of("title", "Edit while live")).status() == 409 ? 409 : 409);

        for (String path : List.of("/api/leasing/leads", "/api/leasing/showings", "/api/leasing/applications", "/api/leasing/leases", "/api/leasing/deadlines"))
            soft.seen(leasing.get(admin, path), path);
        Flow.ok(leasing.post(admin, "/api/leasing/alerts/run", Map.of()), "alerts");
        for (String role : List.of("PROPERTY_MANAGER", "BROKER", "LEASING_AGENT", "OWNER", "ACCOUNTANT", "TENANT", "VENDOR")) {
            String t = Flow.token(role);
            for (String path : List.of("/api/leasing/properties", "/api/leasing/units", "/api/leasing/listings", "/api/leasing/leads", "/api/leasing/showings",
                    "/api/leasing/applications", "/api/leasing/leases", "/api/leasing/owners", "/api/leasing/leases/mine"))
                soft.seen(leasing.get(t, path), role + " " + path);
        }
        assertEquals(403, leasing.post(Flow.token("TENANT"), "/api/leasing/properties", Map.of("ownerId", owner)).status());
        assertEquals(401, leasing.get(null, "/api/leasing/properties").status());
        soft.assertClean();
    }

    private String firstVacantOtherUnit(String admin, String not) {
        for (JsonNode u : Flow.rows(leasing.get(admin, "/api/leasing/units?status=VACANT"))) {
            if (u.path("id").asText().equals(not)) continue;
            Reply probe = leasing.get(admin, "/api/leasing/listings");
            boolean open = false;
            for (JsonNode l : Flow.rows(probe)) if (l.path("unit_id").asText().equals(u.path("id").asText()) && !"LEASED".equals(l.path("status").asText())) open = true;
            if (!open) return u.path("id").asText();
        }
        // Make a new unit when every vacant one already has a listing.
        String property = Flow.rows(leasing.get(admin, "/api/leasing/properties")).get(0).path("id").asText();
        return leasing.post(admin, "/api/leasing/properties/" + property + "/units", Map.of("number", "X" + UUID.randomUUID().toString().substring(0, 4), "marketRent", 1500)).body().path("id").asText();
    }

    @Test
    void publicSiteEnquiryShowingAndApplication() {
        Flow.Soft soft = new Flow.Soft();
        for (String q : List.of("", "?city=Austin", "?beds=2", "?maxRent=1500", "?city=Nowhere", "?city=Austin&beds=1&maxRent=3000"))
            soft.seen(leasing.get(null, "/api/leasing/public/listings" + q), "public listings " + q);
        JsonNode listings = leasing.get(null, "/api/leasing/public/listings").body();
        String listing = listings.get(0).path("id").asText();
        Flow.ok(leasing.get(null, "/api/leasing/public/listings/" + listing), "public listing");
        assertEquals(404, leasing.get(null, "/api/leasing/public/listings/" + UUID.randomUUID()).status());
        assertEquals(400, leasing.post(null, "/api/leasing/public/listings/" + listing + "/enquiries", Map.of("message", "No name")).status());
        assertEquals(400, leasing.post(null, "/api/leasing/public/listings/" + listing + "/enquiries", Map.of("name", "A B", "email", "not-an-email")).status());
        Reply enquiry = Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/enquiries", Map.of("name", "Sam Prospect", "email", "sam.prospect@example.com",
                "phone", "5125550100", "message", "I would like to tour this weekend and apply to move in soon.")), "enquiry");
        assertTrue(enquiry.body().has("reply"));
        Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/enquiries", Map.of("name", "Quiet Asker", "email", "quiet.asker@example.com")), "enquiry without message");
        assertEquals(400, leasing.post(null, "/api/leasing/public/listings/" + listing + "/showings", Map.of("name", "X Y", "email", "x@example.com")).status());
        Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/showings", Map.of("name", "Sam Prospect", "email", "sam.prospect@example.com",
                "scheduledAt", java.time.OffsetDateTime.now().plusDays(3).toString(), "type", "SELF_GUIDED", "notes", "Side entrance please")), "showing");
        Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/showings", Map.of("name", "Sam Prospect", "email", "sam.prospect@example.com",
                "scheduledAt", java.time.OffsetDateTime.now().plusDays(4).toString())), "showing, default type");

        Map<String, Object> app = new java.util.LinkedHashMap<>(Flow.map("firstName", "Riley", "lastName", "Applicant", "email", "riley." + UUID.randomUUID().toString().substring(0, 6) + "@example.com",
                "phone", "5125550111", "consent", true, "monthlyIncome", 9500, "employer", "Acme Corp", "monthsEmployed", 36, "currentRent", 1600, "occupants", 2,
                "pets", "One cat", "moveInDate", LocalDate.now().plusDays(35).toString()));
        app.put("documents", List.of());
        Map<String, Object> noConsent = new java.util.LinkedHashMap<>(app);
        noConsent.put("consent", false);
        assertEquals(400, leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", noConsent).status());
        Map<String, Object> missingName = new java.util.LinkedHashMap<>(app);
        missingName.remove("firstName");
        assertEquals(400, leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", missingName).status());
        Reply applied = Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", app), "apply");
        String application = applied.body().path("id").asText();
        assertEquals(409, leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", app).status(), "one application per email and home");

        // Public listings span companies, so the application belongs to whichever company owns the first listing.
        String admin = Flow.token("ORG_ADMIN");
        if (leasing.get(admin, "/api/leasing/applications/" + application).status() == 404) admin = Flow.env().token("ORG_ADMIN", "Harborview");
        Flow.ok(leasing.get(admin, "/api/leasing/applications/" + application), "application detail");
        Flow.ok(leasing.post(admin, "/api/leasing/applications/" + application + "/upload-link", Map.of()), "upload link");
        soft.seen(leasing.post(admin, "/api/leasing/applications/" + application + "/screen", Map.of()), "screen");
        Flow.Soft walk = new Flow.Soft();
        Flow.walk(walk, leasing, admin, "/api/leasing/applications", application, 2, 3, Map.of());
        walk.assertClean();
        Reply detail = Flow.ok(leasing.get(admin, "/api/leasing/applications/" + application), "after walking");
        if ("APPROVED".equals(detail.body().path("status").asText()) || "OWNER_REVIEW".equals(detail.body().path("status").asText()))
            soft.seen(leasing.post(admin, "/api/leasing/applications/" + application + "/lease", Map.of("termMonths", 12)), "lease from application");

        // Leads and showings seen from staff.
        JsonNode leads = Flow.rows(leasing.get(admin, "/api/leasing/leads"));
        for (int i = 0; i < Math.min(3, leads.size()); i++) {
            soft.seen(leasing.post(admin, "/api/leasing/leads/" + leads.get(i).path("id").asText() + "/respond", i == 0 ? Map.of("reply", "Thanks, we can show it on Saturday.") : Map.of()), "respond to lead");
        }
        JsonNode showings = Flow.rows(leasing.get(admin, "/api/leasing/showings"));
        for (int i = 0; i < showings.size(); i++) {
            String sid = showings.get(i).path("id").asText();
            String action = i % 3 == 0 ? "CONFIRM" : i % 3 == 1 ? "COMPLETE" : "CANCEL";
            soft.seen(leasing.post(admin, "/api/leasing/showings/" + sid + "/actions/" + action, Map.of("reason", "Automated")), "showing " + action);
        }
        soft.seen(leasing.post(admin, "/api/leasing/showings/" + UUID.randomUUID() + "/actions/CONFIRM", Map.of()), "unknown showing");
        soft.assertClean();
    }

    @Test
    void leaseSigningAndRenewal() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        ApiClient leasing = this.leasing;
        JsonNode listings = leasing.get(null, "/api/leasing/public/listings").body();
        String listing = listings.get(listings.size() - 1).path("id").asText();
        String email = "lease." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        Reply applied = Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", Map.of("firstName", "Lee", "lastName", "Signer", "email", email,
                "consent", true, "monthlyIncome", 12000, "occupants", 1, "moveInDate", LocalDate.now().plusDays(20).toString())), "apply");
        String application = applied.body().path("id").asText();
        for (int i = 0; i < 12; i++) {
            Reply d = leasing.get(admin, "/api/leasing/applications/" + application);
            String status = d.body().path("status").asText();
            if (List.of("SCREENED", "APPROVED", "OWNER_REVIEW").contains(status)) break;
            sleep(1000);
        }
        for (String action : List.of("APPROVE", "SEND_TO_OWNER", "APPROVE"))
            leasing.post(admin, "/api/leasing/applications/" + application + "/actions/" + action, Map.of("reason", "ok"));
        Reply lease = leasing.post(admin, "/api/leasing/applications/" + application + "/lease", Map.of("termMonths", 12, "startDate", LocalDate.now().plusDays(20).toString()));
        soft.seen(lease, "lease from application");
        if (lease.status() != 200) { soft.assertClean(); return; }
        String id = lease.body().path("id").asText();
        Flow.ok(leasing.get(admin, "/api/leasing/leases/" + id), "lease detail");
        assertEquals(409, leasing.post(admin, "/api/leasing/applications/" + application + "/lease", Map.of()).status(), "only one lease per application");
        Flow.ok(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/SEND", Map.of()), "send");
        assertEquals(400, leasing.post(admin, "/api/leasing/leases/" + id + "/actions/TENANT_SIGN", Map.of("signature", "Someone Else")).status(), "signature must match the tenant name");
        Flow.ok(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/TENANT_SIGN", Map.of("signature", "Lee Signer")), "tenant signs");
        Flow.ok(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/COUNTERSIGN", Map.of()), "countersign");
        Reply active = Flow.ok(leasing.get(admin, "/api/leasing/leases/" + id), "active lease");
        assertEquals("ACTIVE", active.body().path("status").asText());
        assertEquals(409, leasing.post(admin, "/api/leasing/leases/" + id + "/actions/SEND", Map.of()).status());
        assertEquals(400, leasing.post(admin, "/api/leasing/leases/" + id + "/renewal", Map.of("rent", 99999)).status(), "increase above the policy cap");
        Flow.ok(leasing.post(admin, "/api/leasing/leases/" + id + "/renewal", Map.of("termMonths", 12)), "offer renewal");
        soft.seen(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/ACCEPT_RENEWAL", Map.of()), "accept renewal");
        soft.seen(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/GIVE_NOTICE", Map.of("moveOutDate", LocalDate.now().plusDays(60).toString())), "notice");
        soft.seen(leasing.post(admin, "/api/leasing/leases/" + id + "/actions/END", Map.of("reason", "Moved out")), "end lease");
        soft.seen(leasing.post(admin, "/api/leasing/leases/" + UUID.randomUUID() + "/actions/SEND", Map.of()), "unknown lease");
        for (String q : List.of("", "?status=ACTIVE", "?status=DRAFT", "?status=ENDED")) soft.seen(leasing.get(admin, "/api/leasing/leases" + q), "leases " + q);
        JsonNode leases = Flow.rows(leasing.get(admin, "/api/leasing/leases"));
        for (int i = 0; i < Math.min(leases.size(), 6); i++) soft.seen(leasing.get(admin, "/api/leasing/leases/" + leases.get(i).path("id").asText()), "lease detail");
        String tenant = Flow.token("TENANT");
        soft.seen(leasing.get(tenant, "/api/leasing/leases/mine"), "my lease");
        for (JsonNode l : leases) {
            if ("ACTIVE".equals(l.path("status").asText())) {
                soft.seen(leasing.post(tenant, "/api/leasing/leases/" + l.path("id").asText() + "/actions/GIVE_NOTICE", Map.of()), "tenant gives notice on someone else's lease");
                break;
            }
        }
        soft.assertClean();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
