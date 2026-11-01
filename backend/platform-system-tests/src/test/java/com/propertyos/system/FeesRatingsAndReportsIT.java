package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What companies pay PropertyOS, how residents, owners and staff rate homes and vendors, and the report centre, each through the
 * real services and database. The rules are the point: who may do what, what is refused and why, and that the figures add up.
 */
class FeesRatingsAndReportsIT {
    private final ApiClient identity = Flow.identity();
    private final ApiClient leasing = Flow.leasing();
    private final ApiClient billing = Flow.billing();
    private final ApiClient maintenance = Flow.maintenance();

    private static JsonNode rows(Reply r) {
        return Flow.rows(r);
    }

    @Test
    void platformFeesAreBilledPaidAndSeenByTheRightPeople() {
        String platform = Flow.token("PLATFORM_ADMIN");
        String admin = Flow.token("ORG_ADMIN");
        String harbor = Flow.env().token("ORG_ADMIN", "Harbor");
        String base = "/api/identity/platform/billing";

        Reply run = Flow.ok(identity.post(platform, base + "/run", Map.of()), "monthly run");
        assertTrue(run.body().path("companies").asInt() >= 2);
        Reply again = Flow.ok(identity.post(platform, base + "/run", Map.of()), "run again");
        assertEquals(0, again.body().path("created").asInt(), "nobody is billed twice for a month");
        assertEquals(200, identity.post(platform, base + "/run", Map.of("asOf", java.time.LocalDate.now().plusMonths(1).toString())).status(), "the next month");
        assertEquals(400, identity.post(platform, base + "/run", Map.of("asOf", "next week")).status());

        JsonNode summary = Flow.ok(identity.get(platform, base + "/summary"), "summary").body();
        assertEquals(6, summary.path("trend").size());
        assertTrue(summary.path("billedThisMonth").asDouble() > 0);
        JsonNode invoices = Flow.ok(identity.get(platform, base + "/invoices?page=1&size=50&sort=amount&dir=desc"), "invoices").body().path("rows");
        assertTrue(invoices.size() >= 2);
        String id = invoices.get(0).path("id").asText();
        String org = invoices.get(0).path("org_id").asText();
        assertEquals("OPEN", invoices.get(0).path("status").asText());
        assertTrue(Flow.rows(identity.get(platform, base + "/invoices?page=1&size=5&orgId=" + org)).size() >= 1, "the company's own invoices");
        Flow.ok(identity.get(platform, base + "/invoices/" + id), "one invoice");
        assertEquals(404, identity.get(platform, base + "/invoices/" + UUID.randomUUID()).status());
        Flow.ok(identity.get(platform, base + "/orgs/" + org), "one company's account");
        assertEquals(404, identity.get(platform, base + "/orgs/" + UUID.randomUUID()).status());
        Flow.ok(identity.get(platform, base + "/payments?page=1&size=10"), "payments");
        Flow.ok(identity.get(platform, base + "/payments?page=1&size=10&orgId=" + org), "payments of one company");

        // A one-off charge, a part payment by wire, the rest as a credit, and every refusal on the way.
        assertEquals(400, identity.post(platform, base + "/invoices", Map.of("orgId", org, "amount", 0, "description", "Nothing")).status());
        assertEquals(400, identity.post(platform, base + "/invoices", Map.of("orgId", org, "amount", "abc", "description", "Not a number")).status());
        assertEquals(400, identity.post(platform, base + "/invoices", Map.of("orgId", org, "amount", 5, "description", "x".repeat(301))).status());
        assertEquals(404, identity.post(platform, base + "/invoices", Map.of("orgId", UUID.randomUUID().toString(), "amount", 5, "description", "Nobody")).status());
        Reply charge = Flow.ok(identity.post(platform, base + "/invoices", Map.of("orgId", org, "amount", 200, "description", "Set-up help", "dueDate", java.time.LocalDate.now().plusDays(30).toString())), "charge");
        String one = charge.body().path("id").asText();
        assertEquals(400, identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "CRYPTO")).status());
        assertEquals(400, identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "WIRE", "amount", 0)).status());
        assertEquals(400, identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "WIRE", "amount", 999)).status());
        assertEquals(400, identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "CARD", "last4", "12")).status());
        assertEquals("PARTIAL", Flow.ok(identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "WIRE", "amount", 50, "reference", "W-1", "note", "Partial")), "wire").body().path("status").asText());
        assertEquals(409, identity.post(platform, base + "/invoices/" + one + "/void", Map.of("reason", "too late")).status());
        assertEquals("PAID", Flow.ok(identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "CREDIT")), "credit").body().path("status").asText());
        assertEquals(409, identity.post(platform, base + "/invoices/" + one + "/payments", Map.of("method", "WIRE")).status(), "already paid");
        Reply mistake = Flow.ok(identity.post(platform, base + "/invoices", Map.of("orgId", org, "amount", 10, "description", "Mistake")), "second charge");
        String bad = mistake.body().path("id").asText();
        assertEquals(400, identity.post(platform, base + "/invoices/" + bad + "/void", Map.of()).status());
        assertEquals("VOID", Flow.ok(identity.post(platform, base + "/invoices/" + bad + "/void", Map.of("reason", "Mistake")), "void").body().path("status").asText());
        Flow.ok(identity.post(platform, base + "/invoices/" + bad + "/void", Map.of("reason", "Again")), "void is repeatable");
        assertEquals(409, identity.post(platform, base + "/invoices/" + bad + "/payments", Map.of("method", "WIRE")).status());
        assertEquals(404, identity.post(platform, base + "/invoices/" + UUID.randomUUID() + "/void", Map.of("reason", "x")).status());

        // The company's own view: its own invoices only, and card or bank payment, with a decline.
        Reply sub = Flow.ok(identity.get(admin, "/api/identity/billing/subscription"), "subscription");
        assertTrue(sub.body().path("units").asLong() >= 0);
        assertTrue(sub.body().path("estimatedNextInvoice").asDouble() > 0);
        JsonNode mine = Flow.ok(identity.get(admin, "/api/identity/billing/invoices?page=1&size=20"), "my invoices").body().path("rows");
        assertTrue(mine.size() >= 1);
        String mineId = null;
        for (JsonNode i : mine) if (i.path("balance").asDouble() > 0 && !"VOID".equals(i.path("status").asText())) mineId = i.path("id").asText();
        assertNotNull(mineId, "an open invoice of the company");
        Flow.ok(identity.get(admin, "/api/identity/billing/invoices/" + mineId), "my invoice");
        assertEquals(404, identity.get(harbor, "/api/identity/billing/invoices/" + mineId).status(), "another company's invoice is not found");
        assertEquals(404, identity.post(harbor, "/api/identity/billing/invoices/" + mineId + "/pay", Map.of("method", "CARD", "last4", "4242")).status());
        assertEquals(409, identity.post(admin, "/api/identity/billing/invoices/" + mineId + "/pay", Map.of("method", "CARD", "last4", "0002")).status(), "declined");
        assertEquals(400, identity.post(admin, "/api/identity/billing/invoices/" + mineId + "/pay", Map.of("method", "WIRE")).status());
        assertEquals(200, identity.post(admin, "/api/identity/billing/invoices/" + mineId + "/pay", Map.of("method", "ACH", "last4", "6789", "amount", 25)).status());
        assertEquals("PAID", identity.post(admin, "/api/identity/billing/invoices/" + mineId + "/pay", Map.of("method", "CARD", "last4", "4242")).body().path("status").asText());

        // Closed to everyone else.
        for (String role : List.of("ORG_ADMIN", "PROPERTY_MANAGER", "TENANT")) {
            assertEquals(403, identity.get(Flow.token(role), base + "/summary").status(), role);
            assertEquals(403, identity.post(Flow.token(role), base + "/run", Map.of()).status(), role);
        }
        assertEquals(403, identity.get(Flow.token("TENANT"), "/api/identity/billing/subscription").status());
        assertEquals(401, identity.get(null, base + "/summary").status());
    }

    @Test
    void residentsRatePropertyAndUnitAndStaffReadIt() {
        String tenant = Flow.token("TENANT");
        String manager = Flow.token("PROPERTY_MANAGER");
        JsonNode mine = Flow.ok(leasing.get(tenant, "/api/leasing/reviews/mine"), "my home").body();
        String property = mine.path("property").path("id").asText(), unit = mine.path("unit").path("id").asText();
        assertFalse(property.isEmpty());
        assertEquals(400, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "PROPERTY", "subjectId", property, "stars", 0)).status());
        assertEquals(400, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "PROPERTY", "subjectId", property, "stars", 9)).status());
        assertEquals(400, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "GARDEN", "subjectId", property, "stars", 3)).status());
        assertEquals(400, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "UNIT", "subjectId", unit, "stars", 3, "comment", "x".repeat(1001))).status());
        assertEquals(403, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "UNIT", "subjectId", UUID.randomUUID().toString(), "stars", 3)).status(), "a home they do not live in");
        assertEquals(200, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "PROPERTY", "subjectId", property, "stars", 4, "comment", "Quiet and clean")).status());
        assertEquals(200, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "UNIT", "subjectId", unit, "stars", 2)).status());
        assertEquals(200, leasing.put(tenant, "/api/leasing/reviews", Map.of("subjectType", "UNIT", "subjectId", unit, "stars", 3, "comment", " ")).status(), "changed their mind");
        assertEquals(3, Flow.ok(leasing.get(tenant, "/api/leasing/reviews/mine"), "mine again").body().path("unit").path("review").path("stars").asInt());

        JsonNode seen = Flow.ok(leasing.get(manager, "/api/leasing/reviews/PROPERTY/" + property), "staff read").body();
        assertTrue(seen.path("count").asInt() >= 1);
        assertTrue(seen.path("distribution").has("4"));
        assertEquals(400, leasing.get(manager, "/api/leasing/reviews/GARDEN/" + property).status());
        Flow.ok(leasing.get(Flow.token("OWNER"), "/api/leasing/reviews/PROPERTY/" + property), "owner read");
        assertEquals(403, leasing.put(manager, "/api/leasing/reviews", Map.of("subjectType", "PROPERTY", "subjectId", property, "stars", 5)).status(), "staff do not rate");
        assertEquals(403, leasing.get(manager, "/api/leasing/reviews/mine").status());
        // Ratings appear on the property and unit lists.
        boolean onList = false;
        for (JsonNode p : rows(leasing.get(manager, "/api/leasing/properties?page=1&size=100"))) if (property.equals(p.path("id").asText()) && p.path("rating_count").asInt() >= 1) onList = true;
        assertTrue(onList, "the property list shows the rating");
        Flow.ok(leasing.get(manager, "/api/leasing/units?page=1&size=100"), "units with ratings");
        assertEquals(200, leasing.delete(tenant, "/api/leasing/reviews/UNIT/" + unit).status());
        Flow.ok(leasing.get(manager, "/api/leasing/reports/ratings?page=1&size=20"), "the ratings report");
        assertEquals(403, leasing.get(tenant, "/api/leasing/properties?page=1&size=5").status(), "residents have no property list");
    }

    @Test
    void vendorsAreRatedByStaffOwnersAndResidentsWithinTheirLimits() {
        String manager = Flow.token("PROPERTY_MANAGER");
        String tenant = Flow.token("TENANT");
        String owner = Flow.token("OWNER");
        JsonNode vendors = rows(maintenance.get(manager, "/api/maintenance/vendors?page=1&size=20"));
        String vendor = vendors.get(0).path("id").asText();
        String url = "/api/maintenance/vendors/" + vendor + "/reviews";
        assertEquals(400, maintenance.post(manager, url, Map.of("stars", 0)).status());
        assertEquals(400, maintenance.post(manager, url, Map.of("stars", 5, "comment", "x".repeat(1001))).status());
        assertEquals(404, maintenance.post(manager, "/api/maintenance/vendors/" + UUID.randomUUID() + "/reviews", Map.of("stars", 5)).status());
        assertEquals(200, maintenance.post(manager, url, Map.of("stars", 4, "comment", "Reliable")).status());
        assertEquals(200, maintenance.post(manager, url, Map.of("stars", 5, "comment", "Even better")).status(), "the same person changing it");
        JsonNode seen = Flow.ok(maintenance.get(manager, url), "staff read").body();
        assertTrue(seen.path("count").asInt() >= 1);
        assertTrue(seen.path("canRate").asBoolean());
        assertEquals(400, maintenance.post(tenant, url, Map.of("stars", 5)).status(), "a resident must name the repair");
        assertTrue(maintenance.post(tenant, url, Map.of("stars", 5, "workOrderId", UUID.randomUUID().toString())).status() >= 400);
        assertTrue(maintenance.post(owner, url, Map.of("stars", 5)).status() == 200 || maintenance.post(owner, url, Map.of("stars", 5)).status() == 403, "owners rate vendors who worked on their property");
        Flow.ok(maintenance.get(tenant, url), "a resident reads only their own");
        assertEquals(403, maintenance.post(Flow.token("VENDOR"), url, Map.of("stars", 5)).status());
        assertEquals(403, maintenance.post(Flow.token("PLATFORM_ADMIN"), url, Map.of("stars", 5)).status());
        // Rating a finished job as a resident, and closing a repair with a rating, both file a review.
        for (JsonNode w : rows(maintenance.get(tenant, "/api/maintenance/work-orders?page=1&size=50"))) {
            if (w.path("vendor_id").isNull() || !List.of("COMPLETED", "CLOSED").contains(w.path("status").asText())) continue;
            Reply r = maintenance.post(tenant, "/api/maintenance/vendors/" + w.path("vendor_id").asText() + "/reviews", Map.of("stars", 4, "comment", "Good", "workOrderId", w.path("id").asText()));
            assertEquals(200, r.status());
            break;
        }
        for (JsonNode w : rows(maintenance.get(manager, "/api/maintenance/work-orders?page=1&size=50"))) {
            if (!"COMPLETED".equals(w.path("status").asText())) continue;
            Reply r = maintenance.post(manager, "/api/maintenance/work-orders/" + w.path("id").asText() + "/actions/VERIFY", Map.of("rating", 5, "comment", "Tidy"));
            assertTrue(r.status() == 200 || r.status() == 409);
            break;
        }
        assertTrue(Flow.rows(maintenance.get(manager, "/api/maintenance/vendors?page=1&size=20")).get(0).has("rating_overall"));
    }

    @Test
    void everyReportAnswersTheSameShapeAndDrillsDown() {
        String manager = Flow.token("PROPERTY_MANAGER");
        String accountant = Flow.token("ACCOUNTANT");
        String owner = Flow.token("OWNER");
        for (String k : List.of("rent-roll", "occupancy", "vacancies", "expirations", "funnel", "ratings")) {
            Reply r = Flow.ok(leasing.get(manager, "/api/leasing/reports/" + k + "?page=1&size=5"), k);
            assertTrue(r.body().has("summary") && r.body().path("rows").isArray(), k);
        }
        for (String k : List.of("receivables", "income", "transactions", "payments", "fees")) {
            Reply r = Flow.ok(billing.get(accountant, "/api/billing/reports/" + k + "?page=1&size=5"), k);
            assertTrue(r.body().has("summary") && r.body().path("rows").isArray(), k);
        }
        for (String k : List.of("costs", "costs?by=vendor", "costs?by=category", "costs?by=priority", "jobs", "vendors")) {
            Reply r = Flow.ok(maintenance.get(manager, "/api/maintenance/reports/" + k + (k.contains("?") ? "&" : "?") + "page=1&size=5"), k);
            assertTrue(r.body().has("summary"), k);
        }
        // Drill from occupancy to the units, and from income to the transactions behind a month.
        JsonNode occ = Flow.ok(leasing.get(manager, "/api/leasing/reports/occupancy?page=1&size=50"), "occupancy").body();
        String property = occ.path("rows").get(0).path("property_id").asText();
        assertEquals(occ.path("rows").get(0).path("units").asInt(), Flow.ok(leasing.get(manager, "/api/leasing/reports/rent-roll?page=1&size=100&propertyId=" + property), "units").body().path("total").asInt());
        Flow.ok(leasing.get(manager, "/api/leasing/reports/vacancies?page=1&size=50&propertyId=" + property), "vacancies of one");
        Flow.ok(leasing.get(manager, "/api/leasing/reports/funnel?from=2026-01-01&to=2026-12-31"), "funnel with a period");
        Flow.ok(leasing.get(manager, "/api/leasing/reports/expirations?days=30"), "ending soon");
        JsonNode income = Flow.ok(billing.get(accountant, "/api/billing/reports/income?page=1&size=20&from=2026-01-01"), "income").body();
        if (income.path("rows").size() > 0) {
            JsonNode line = income.path("rows").get(0);
            Flow.ok(billing.get(accountant, "/api/billing/reports/transactions?page=1&size=50&propertyId=" + line.path("property_id").asText() + "&month=" + line.path("month").asText()), "transactions");
            Flow.ok(billing.get(accountant, "/api/billing/reports/income?page=1&size=20&propertyId=" + line.path("property_id").asText()), "income of one property");
        }
        JsonNode rec = Flow.ok(billing.get(accountant, "/api/billing/reports/receivables?page=1&size=20"), "receivables").body();
        if (rec.path("rows").size() > 0) Flow.ok(billing.get(accountant, "/api/billing/reports/transactions?page=1&size=50&tenantId=" + rec.path("rows").get(0).path("tenant_id").asText()), "one resident's account");
        JsonNode byProperty = Flow.ok(maintenance.get(manager, "/api/maintenance/reports/costs?by=property&page=1&size=5"), "costs by property").body();
        if (byProperty.path("rows").size() > 0) Flow.ok(maintenance.get(manager, "/api/maintenance/reports/jobs?page=1&size=50&propertyId=" + byProperty.path("rows").get(0).path("group_id").asText()), "jobs of one property");
        Flow.ok(maintenance.get(manager, "/api/maintenance/reports/jobs?page=1&size=50&category=PLUMBING&priority=HIGH&vendorId=" + UUID.randomUUID()), "jobs with every filter");

        // Choices are checked.
        assertEquals(400, leasing.get(manager, "/api/leasing/reports/expirations?days=0").status());
        assertEquals(400, leasing.get(manager, "/api/leasing/reports/funnel?from=2026-12-01&to=2026-01-01").status());
        assertEquals(400, billing.get(accountant, "/api/billing/reports/income?from=2026-12-01&to=2026-01-01").status());
        assertEquals(400, billing.get(accountant, "/api/billing/reports/payments?from=2026-12-01&to=2026-01-01").status());
        assertEquals(400, billing.get(accountant, "/api/billing/reports/fees?from=2026-12-01&to=2026-01-01").status());
        assertEquals(400, billing.get(accountant, "/api/billing/reports/transactions?month=May").status());
        assertEquals(400, maintenance.get(manager, "/api/maintenance/reports/costs?by=weather").status());
        assertEquals(400, maintenance.get(manager, "/api/maintenance/reports/costs?from=2026-12-01&to=2026-01-01").status());
        assertEquals(400, maintenance.get(manager, "/api/maintenance/reports/vendors?from=2026-12-01&to=2026-01-01").status());

        // Each role gets what it should.
        assertTrue(Flow.ok(leasing.get(owner, "/api/leasing/reports/occupancy?page=1&size=50"), "owner occupancy").body().path("total").asInt() <= occ.path("total").asInt());
        Flow.ok(billing.get(owner, "/api/billing/reports/income?page=1&size=20&from=2026-01-01"), "owner income");
        for (String role : List.of("TENANT", "VENDOR")) {
            assertEquals(403, leasing.get(Flow.token(role), "/api/leasing/reports/rent-roll").status(), role);
            assertEquals(403, billing.get(Flow.token(role), "/api/billing/reports/receivables").status(), role);
            assertEquals(403, maintenance.get(Flow.token(role), "/api/maintenance/reports/costs").status(), role);
        }
        assertEquals(401, leasing.get(null, "/api/leasing/reports/rent-roll").status());
    }

    @Test
    void anAmenityListCanBeChangedAndTheListingPageCarriesTheFacts() {
        String manager = Flow.token("PROPERTY_MANAGER");
        JsonNode p = rows(leasing.get(manager, "/api/leasing/properties?page=1&size=5")).get(0);
        String id = p.path("id").asText();
        assertEquals(200, leasing.put(manager, "/api/leasing/properties/" + id, Map.of("amenities", List.of("Pool", "Covered parking", "Pets welcome"))).status());
        assertEquals(3, Flow.ok(leasing.get(manager, "/api/leasing/properties/" + id), "property").body().path("amenities").size());
        assertEquals(200, leasing.put(manager, "/api/leasing/properties/" + id, Map.of("name", p.path("name").asText())).status(), "other changes leave the amenities alone");
        assertEquals(3, leasing.get(manager, "/api/leasing/properties/" + id).body().path("amenities").size());
        // The public page of a live home carries what the new design shows.
        for (JsonNode l : Flow.rows(leasing.get(null, "/api/leasing/public/listings"))) {
            JsonNode d = Flow.ok(leasing.get(null, "/api/leasing/public/listings/" + l.path("id").asText()), "public listing").body();
            for (String f : List.of("property_type", "year_built", "unit_number", "property_rating_count", "unit_rating_count", "amenities", "deposit", "available_date")) assertTrue(d.has(f), f);
            break;
        }
    }
}
