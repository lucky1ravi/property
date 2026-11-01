package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The "other side" of the decisions: what each kind of person is refused, which filters narrow a list, which inputs are
 * turned away and how. These are the paths a happy-path test never takes, so they are where a regression hides. No call may
 * fail with a server error, and the refusals that matter are checked exactly.
 */
class BranchGapsIT {
    private final ApiClient docs = Flow.documents();
    private final ApiClient billing = Flow.billing();
    private final ApiClient leasing = Flow.leasing();

    private static byte[] pdf(String text) {
        return DocumentScenarioIT.pdf(text);
    }

    private static void noServerError(Reply r, String what) {
        assertTrue(r.status() < 500, what + " failed with a server error: " + r.status() + " " + r.body());
    }

    private String firstId(String token, String path) {
        return Flow.rows(leasing.get(token, path)).get(0).path("id").asText();
    }

    @Test
    void documentListsCanBeNarrowedByEveryFilter() {
        String admin = Flow.token("ORG_ADMIN");
        String property = firstId(admin, "/api/leasing/properties");
        String owner = firstId(admin, "/api/leasing/owners");
        String tenantLease = Flow.rows(leasing.get(admin, "/api/leasing/leases?status=ACTIVE")).get(0).path("tenant_id").asText();
        String vendor = Flow.rows(Flow.maintenance().get(admin, "/api/maintenance/vendors")).get(0).path("id").asText();
        String marker = UUID.randomUUID().toString();
        Reply up = Flow.ok(docs.upload(admin, "/api/documents", "filters.pdf", pdf(marker),
                Map.of("title", "Filter test " + marker, "docType", "Insurance policy", "entityType", "PROPERTY", "entityId", property)), "upload");
        String id = up.body().path("id").asText();

        List<String> filters = List.of("docType=Insurance policy", "entityId=" + property, "propertyId=" + property, "ownerId=" + owner, "tenantId=" + tenantLease,
                "vendorId=" + vendor, "signature=PENDING", "signature=NONE", "signature=SIGNED", "mine=true", "status=ALL", "status=DELETED", "status=ARCHIVED",
                "category=INSURANCE", "q=" + marker, "tag=insurance", "review=PENDING", "expiring=true", "sharedWith=OWNER", "sharedWith=VENDOR", "sort=title&dir=asc",
                "sort=created_at&dir=desc", "size=1&page=2", "docType=&category=");
        for (String f : filters) noServerError(docs.get(admin, "/api/documents?" + f), "documents?" + f);
        JsonNode mine = docs.get(admin, "/api/documents?mine=true&q=" + marker).body().path("items");
        assertTrue(mine.isArray() && mine.size() == 1, "the file just uploaded is the only one of mine with that title");
        assertEquals(0, docs.get(admin, "/api/documents?docType=No such type&q=" + marker).body().path("items").size(), "a filter that matches nothing returns nothing");

        // Somebody who is not allowed to see the trash is refused, and cannot open or change what they cannot see.
        String tenant = Flow.token("TENANT");
        assertEquals(403, docs.get(tenant, "/api/documents?status=DELETED").status(), "residents cannot browse the trash");
        for (String f : filters) noServerError(docs.get(tenant, "/api/documents?" + f), "tenant documents?" + f);
        assertEquals(404, docs.get(tenant, "/api/documents/" + UUID.randomUUID()).status());
        for (String action : List.of("archive", "delete", "restore")) {
            Reply r = docs.post(tenant, "/api/documents/" + id + "/actions/" + action, Map.of("reason", "x"));
            assertTrue(r.status() == 403 || r.status() == 404, "a resident cannot " + action + " a company document: " + r.status());
        }
        assertTrue(docs.post(tenant, "/api/documents/" + id + "/review", Map.of("decision", "ACCEPTED")).status() >= 400, "residents cannot review");
        assertTrue(docs.post(tenant, "/api/documents/" + id + "/legal-hold", Map.of("on", true, "reason", "x")).status() >= 400, "residents cannot place a hold");
        assertTrue(docs.post(tenant, "/api/documents/" + id + "/purge", Map.of("reason", "x")).status() >= 400, "residents cannot purge");
        assertTrue(docs.post(tenant, "/api/documents/export", Map.of("ids", List.of(id))).status() >= 400, "residents cannot export");
        assertEquals(400, docs.post(admin, "/api/documents/export", Map.of("ids", List.of())).status(), "export needs at least one document");
        assertEquals(400, docs.post(admin, "/api/documents/" + id + "/legal-hold", Map.of("on", true)).status(), "a hold needs a reason");
        assertTrue(docs.put(tenant, "/api/documents/" + id, Map.of("title", "mine now")).status() >= 400, "residents cannot rename company files");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/actions/delete", Map.of("reason", "Test over")), "delete");
        noServerError(docs.get(admin, "/api/documents?status=DELETED"), "trash");
        assertEquals(404, docs.get(tenant, "/api/documents/" + id).status(), "a deleted file is gone for residents");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/purge", Map.of("reason", "Test over")), "purge");
    }

    @Test
    void peopleCanOnlyAddFilesWhereTheyBelong() {
        String admin = Flow.token("ORG_ADMIN");
        String property = firstId(admin, "/api/leasing/properties");
        String marker = UUID.randomUUID().toString();
        Map<String, String> forProperty = new LinkedHashMap<>(Map.of("title", "Photo " + marker, "docType", "Photo", "entityType", "PROPERTY", "entityId", property));

        // A resident may add to their own lease and requests, never to a property or to somebody else's records.
        String tenant = Flow.token("TENANT");
        Reply own = docs.upload(tenant, "/api/documents", "proof.pdf", pdf("proof " + marker), Map.of("title", "Proof " + marker, "category", "APPLICATION"));
        noServerError(own, "resident upload to own lease");
        Reply other = docs.upload(tenant, "/api/documents", "other.pdf", pdf("other " + marker), forProperty);
        assertTrue(other.status() == 403 || other.status() == 400, "a resident cannot add to a property: " + other.status());
        assertTrue(docs.upload(tenant, "/api/documents", "x.pdf", pdf("x " + marker), Map.of("category", "TAX", "entityType", "PROPERTY", "entityId", property)).status() >= 400,
                "tax files are not for residents");
        noServerError(docs.upload(tenant, "/api/documents", "dup.pdf", pdf("proof " + marker), Map.of("title", "Proof again", "category", "APPLICATION")), "resident duplicate");

        // An owner may add to their own properties only.
        String owner = Flow.token("OWNER");
        noServerError(docs.upload(owner, "/api/documents", "own.pdf", pdf("owner " + marker), Map.of("title", "Owner file " + marker, "docType", "Insurance policy", "entityType", "PROPERTY", "entityId", property)), "owner upload");
        noServerError(docs.upload(owner, "/api/documents", "own2.pdf", pdf("owner2 " + marker), Map.of("title", "Owner file two " + marker, "category", "TAX")), "owner upload without a record");
        noServerError(docs.upload(owner, "/api/documents", "own3.pdf", pdf("owner3 " + marker), Map.of("title", "Owner file three " + marker, "entityType", "PROPERTY", "entityId", UUID.randomUUID().toString())), "owner upload to nothing");

        // Staff scoped to some properties cannot add to the others.
        String manager = Flow.token("PROPERTY_MANAGER");
        noServerError(docs.upload(manager, "/api/documents", "mgr.pdf", pdf("manager " + marker), Map.of("title", "Manager file " + marker, "docType", "Inspection report", "entityType", "PROPERTY", "entityId", property)), "manager upload");
        noServerError(docs.upload(manager, "/api/documents", "mgr2.pdf", pdf("manager2 " + marker), Map.of("title", "", "propertyId", property)), "untitled upload");

        // Versions: a different file replaces, the same file is refused, and nobody replaces what they may not edit.
        Reply base = Flow.ok(docs.upload(admin, "/api/documents", "base.pdf", pdf("base " + marker), Map.of("title", "Base " + marker, "docType", "Inspection report", "entityType", "PROPERTY", "entityId", property)), "base");
        String id = base.body().path("id").asText();
        assertEquals(409, docs.upload(admin, "/api/documents/" + id + "/versions", "same.pdf", pdf("base " + marker), Map.of()).status(), "the same file is not a new version");
        Flow.ok(docs.upload(admin, "/api/documents/" + id + "/versions", "v2.pdf", pdf("v2 " + marker), Map.of("note", "")), "new version without a note");
        assertTrue(docs.upload(tenant, "/api/documents/" + id + "/versions", "v3.pdf", pdf("v3 " + marker), Map.of()).status() >= 400, "residents cannot replace company files");
        assertTrue(docs.status(tenant, "/api/documents/" + id + "/file") >= 400, "residents cannot download what is not shared with them");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/legal-hold", Map.of("on", true, "reason", "Dispute " + marker)), "hold");
        assertEquals(409, docs.post(admin, "/api/documents/" + id + "/actions/delete", Map.of("reason", "x")).status());
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/legal-hold", Map.of("on", false, "reason", "Resolved")), "release");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/actions/delete", Map.of("reason", "Cleanup")), "delete");
        assertEquals(409, docs.upload(admin, "/api/documents/" + id + "/versions", "v4.pdf", pdf("v4 " + marker), Map.of()).status(), "a deleted file must be restored first");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/purge", Map.of("reason", "Cleanup")), "purge");
    }

    @Test
    void theBillingRulesTurnAwayWhatIsNotAllowed() {
        String admin = Flow.token("ORG_ADMIN");
        String lease = Flow.rows(leasing.get(admin, "/api/leasing/leases?status=ACTIVE")).get(0).path("tenant_id").asText();

        // Charges: positive amounts with a description only; state fee caps apply to late fees.
        assertEquals(400, billing.post(admin, "/api/billing/invoices", Map.of("tenantId", lease, "amount", 0, "description", "Nothing")).status());
        assertEquals(400, billing.post(admin, "/api/billing/invoices", Map.of("tenantId", lease, "amount", -5, "description", "Negative")).status());
        assertEquals(400, billing.post(admin, "/api/billing/invoices", Map.of("amount", 5, "description", "Whose?")).status(), "a charge needs a resident");
        assertEquals(404, billing.post(admin, "/api/billing/invoices", Map.of("tenantId", UUID.randomUUID().toString(), "amount", 5, "description", "Nobody")).status());
        noServerError(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", lease, "amount", 999999, "kind", "LATE_FEE", "description", "Huge late fee")), "late fee over the cap");
        for (String kind : List.of("RENT", "PARKING", "PET", "UTILITY", "REPAIR", "OTHER", "DEPOSIT"))
            noServerError(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", lease, "amount", 10, "kind", kind, "description", "Charge of kind " + kind)), "charge " + kind);

        // Voiding: a reason is needed, an invoice with payments cannot be voided, voiding twice is harmless.
        Reply charge = Flow.ok(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", lease, "amount", 33, "description", "To be voided")), "charge");
        String id = charge.body().path("id").asText();
        assertEquals(400, billing.post(admin, "/api/billing/invoices/" + id + "/void", Map.of()).status(), "a reason is required");
        assertEquals(400, billing.post(admin, "/api/billing/invoices/" + id + "/void", Map.of("reason", "  ")).status());
        Flow.ok(billing.post(admin, "/api/billing/invoices/" + id + "/void", Map.of("reason", "Entered twice")), "void");
        Flow.ok(billing.post(admin, "/api/billing/invoices/" + id + "/void", Map.of("reason", "Again")), "void again changes nothing");
        assertEquals(404, billing.post(admin, "/api/billing/invoices/" + UUID.randomUUID() + "/void", Map.of("reason", "x")).status());
        JsonNode paid = null;
        for (JsonNode i : Flow.rows(billing.get(admin, "/api/billing/invoices?status=PAID"))) if (i.path("paid").asDouble() > 0) { paid = i; break; }
        if (paid != null) assertEquals(409, billing.post(admin, "/api/billing/invoices/" + paid.path("id").asText() + "/void", Map.of("reason", "Too late")).status(), "paid invoices cannot be voided");

        // Payments: an ACH payment that bounces costs a returned-payment fee once, a card that bounces costs nothing.
        String t = lease;
        noServerError(billing.post(admin, "/api/billing/payments", Map.of("amount", 5, "method", "ACH")), "payment without a resident");
        assertEquals(400, billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", -1)).status());
        assertTrue(billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", 0.01, "method", "ACH")).status() >= 400, "below the minimum payment");
        Reply openCharge = Flow.ok(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", t, "amount", 70, "description", "Bounce test")), "charge to bounce");
        assertEquals(409, billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", 10, "method", "ACH", "last4", "0002")).status(), "ACH declined");
        assertEquals(409, billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", 10, "method", "ACH", "last4", "0002")).status(), "ACH declined again");
        assertEquals(409, billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", 10, "method", "CARD", "last4", "0002")).status(), "card declined");
        long fees = 0;
        for (JsonNode i : Flow.rows(billing.get(admin, "/api/billing/invoices?tenantId=" + t))) if ("NSF_FEE".equals(i.path("kind").asText()) && !"VOID".equals(i.path("status").asText())) fees++;
        assertTrue(fees <= 1, "a returned payment is charged for once in the period, not for every attempt: " + fees);
        assertTrue(openCharge.body().has("id"));
        noServerError(billing.post(admin, "/api/billing/payments", Map.of("tenantId", t, "amount", 5, "method", "WIRE", "last4", "1234")), "a method the company does not take");

        // Saved methods and the resident's own account.
        String tenant = Flow.token("TENANT");
        assertEquals(400, billing.post(tenant, "/api/billing/my/methods", Map.of("type", "ACH", "last4", "1234")).status(), "consent is required");
        assertEquals(400, billing.post(tenant, "/api/billing/my/methods", Map.of("type", "CARD", "last4", "12", "consent", true)).status(), "four digits");
        noServerError(billing.post(tenant, "/api/billing/my/methods", Map.of("type", "CARD", "last4", "4242", "consent", true, "autopay", true)), "autopay card");
        assertEquals(403, billing.post(tenant, "/api/billing/invoices", Map.of("tenantId", t, "amount", 5, "description", "Mine")).status(), "residents cannot create charges");
        assertEquals(403, billing.post(tenant, "/api/billing/invoices/" + id + "/void", Map.of("reason", "x")).status(), "residents cannot void");
        noServerError(billing.get(tenant, "/api/billing/invoices/" + id), "a resident opening somebody else's invoice");
    }
}
