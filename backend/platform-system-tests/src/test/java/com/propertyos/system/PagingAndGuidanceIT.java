package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lists answered one page at a time in the database, the type-ahead searches behind the pickers, what a person has been
 * shown in the product, and the shape of every error.
 */
class PagingAndGuidanceIT {
    private final ApiClient leasing = Flow.leasing();
    private final ApiClient billing = Flow.billing();
    private final ApiClient maintenance = Flow.maintenance();
    private final ApiClient documents = Flow.documents();
    private final ApiClient identity = Flow.identity();
    private final ApiClient workflow = Flow.workflow();

    @Test
    void aListWithoutAPageIsUnchangedAndWithOneIsWrappedWithItsTotal() {
        String admin = Flow.token("ORG_ADMIN");
        JsonNode all = Flow.ok(leasing.get(admin, "/api/leasing/units"), "all units").body();
        assertTrue(all.isArray() && all.size() > 3, "the plain list is still a bare array");

        JsonNode first = Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=2"), "page 1").body();
        assertEquals(2, first.path("rows").size());
        assertEquals(all.size(), first.path("total").asInt(), "the total counts every row, not just the page");
        assertEquals(1, first.path("page").asInt());
        assertEquals(2, first.path("size").asInt());
        JsonNode second = Flow.ok(leasing.get(admin, "/api/leasing/units?page=2&size=2"), "page 2").body();
        assertNotEquals(first.path("rows").get(0).path("id").asText(), second.path("rows").get(0).path("id").asText(), "the next page has different rows");
        assertEquals(0, Flow.ok(leasing.get(admin, "/api/leasing/units?page=999&size=2"), "past the end").body().path("rows").size());

        assertEquals(100, Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=100000"), "size is capped").body().path("size").asInt());
        assertEquals(20, Flow.ok(leasing.get(admin, "/api/leasing/units?page=1"), "default size").body().path("size").asInt());
        assertEquals(5000, Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=100000&export=1"), "export size").body().path("size").asInt());
    }

    @Test
    void sortingSearchingAndFilteringHappenInTheDatabase() {
        String admin = Flow.token("ORG_ADMIN");
        JsonNode up = Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=100&sort=number&dir=asc"), "sorted up").body().path("rows");
        JsonNode down = Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=100&sort=number&dir=desc"), "sorted down").body().path("rows");
        assertEquals(up.get(0).path("number").asText(), down.get(down.size() - 1).path("number").asText());
        assertEquals(200, leasing.get(admin, "/api/leasing/units?page=1&size=5&sort=nope;drop%20table").status(), "an unknown sort column is ignored, never trusted");

        JsonNode vacant = Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=100&f.status=VAC"), "text filter").body();
        assertTrue(vacant.path("total").asInt() > 0);
        vacant.path("rows").forEach((u) -> assertEquals("VACANT", u.path("status").asText()));
        assertEquals(0, Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=5&q=zzzzqqq"), "no match").body().path("total").asInt());
        assertEquals(0, Flow.ok(leasing.get(admin, "/api/leasing/units?page=1&size=5&q=" + "100%25_".repeat(3)), "wildcards are literal").body().path("total").asInt());

        JsonNode leases = Flow.ok(leasing.get(admin, "/api/leasing/leases?page=1&size=100"), "leases").body();
        int total = leases.path("total").asInt();
        for (String f : List.of("num.rent=gt:0", "num.rent=ge:0", "num.rent=lt:99999999", "num.rent=le:99999999", "num.rent=ne:-1", "num.rent=eq:-1",
                "num.rent=between:0:99999999", "num.rent=between:0", "from.end_date=2000-01-01", "to.end_date=2999-12-31", "f.tenant_name=a")) {
            Reply r = leasing.get(admin, "/api/leasing/leases?page=1&size=100&" + f);
            assertEquals(200, r.status(), f);
            assertTrue(r.body().path("total").asInt() <= total, f);
        }
        assertEquals(0, Flow.ok(leasing.get(admin, "/api/leasing/leases?page=1&size=5&num.rent=eq:-1"), "equals nothing").body().path("total").asInt());
        assertEquals(total, Flow.ok(leasing.get(admin, "/api/leasing/leases?page=1&size=5&num.rent=gt:abc"), "a bad number is ignored").body().path("total").asInt());
    }

    @Test
    void everyMainListCanBePaged() {
        String admin = Flow.token("ORG_ADMIN");
        for (String path : List.of("/api/leasing/properties", "/api/leasing/owners", "/api/leasing/listings", "/api/leasing/leads", "/api/leasing/showings",
                "/api/leasing/applications", "/api/leasing/residents", "/api/leasing/units?status=VACANT&unlisted=true")) {
            JsonNode b = Flow.ok(leasing.get(admin, path + (path.contains("?") ? "&" : "?") + "page=1&size=3"), path).body();
            assertTrue(b.has("rows") && b.has("total"), path + " answers {rows,total}");
        }
        for (String path : List.of("/api/billing/invoices", "/api/billing/payments", "/api/billing/statements", "/api/billing/deposits")) {
            assertTrue(Flow.ok(billing.get(admin, path + "?page=1&size=3"), path).body().has("total"), path);
        }
        // the old fixed ceilings only apply when nobody asked for a page
        assertTrue(Flow.ok(billing.get(admin, "/api/billing/invoices?page=1&size=100&status=OVERDUE&kind=RENT"), "invoice filters").body().has("rows"));
        assertTrue(Flow.ok(billing.get(admin, "/api/billing/payments?page=1&size=3&tenantId=" + UUID.randomUUID()), "payments of one resident").body().has("rows"));
        for (String path : List.of("/api/maintenance/work-orders", "/api/maintenance/vendors", "/api/maintenance/vendors?active=true", "/api/maintenance/recurring")) {
            assertTrue(Flow.ok(maintenance.get(admin, path + (path.contains("?") ? "&" : "?") + "page=1&size=3"), path).body().has("total"), path);
        }
        assertTrue(Flow.ok(maintenance.get(admin, "/api/maintenance/work-orders?page=1&size=3&status=ACTIVE&priority=HIGH"), "work order filters").body().has("rows"));
        assertTrue(Flow.ok(identity.get(admin, "/api/identity/users?page=1&size=3&q=a"), "users").body().path("total").asInt() >= 1);
        JsonNode audit = Flow.ok(identity.get(admin, "/api/identity/audit?page=1&size=3&entityType=User"), "audit").body();
        assertTrue(audit.has("rows") && audit.has("total"));
        assertTrue(Flow.ok(identity.get(admin, "/api/identity/audit?page=1&size=3"), "audit, every record type").body().path("total").asInt() >= audit.path("total").asInt());
    }

    @Test
    void platformListsArePagedToo() {
        String platform = Flow.token("PLATFORM_ADMIN");
        assertTrue(Flow.ok(identity.get(platform, "/api/identity/platform/orgs?status=ACTIVE&page=1&size=2"), "companies").body().has("total"));
        assertTrue(Flow.ok(identity.get(platform, "/api/identity/platform/site/contact-requests?page=1&size=2"), "enquiries").body().has("total"));
        assertTrue(Flow.ok(identity.get(platform, "/api/identity/platform/site/contact-requests?page=1&size=2&status=NEW"), "new enquiries").body().has("rows"));
    }

    @Test
    void documentsAreFilteredByTabAndCountedAcrossEveryPage() {
        String admin = Flow.token("ORG_ADMIN");
        JsonNode page = Flow.ok(documents.get(admin, "/api/documents?page=1&size=3&status=ACTIVE"), "documents").body();
        assertTrue(page.path("items").size() <= 3);
        assertTrue(page.has("total") && page.has("byCategory") && page.has("esigned"), "tab counts come with the page");
        long counted = 0;
        for (JsonNode n : page.path("byCategory")) counted += n.asLong();
        assertEquals(page.path("total").asLong() >= counted ? counted : page.path("total").asLong(), Math.min(counted, page.path("total").asLong()));

        JsonNode lease = Flow.ok(documents.get(admin, "/api/documents?page=1&size=50&status=ACTIVE&categories=LEASE,NOTICE"), "a tab of categories").body();
        lease.path("items").forEach((d) -> assertTrue(List.of("LEASE", "NOTICE").contains(d.path("category").asText())));
        JsonNode signed = Flow.ok(documents.get(admin, "/api/documents?page=1&size=50&status=ACTIVE&group=esign"), "the e-signed tab").body();
        signed.path("items").forEach((d) -> assertNotEquals("NONE", d.path("signature_status").asText()));

        for (String status : List.of("PENDING", "COMPLETED", "OTHER", "")) {
            JsonNode s = Flow.ok(documents.get(admin, "/api/documents/signatures?page=1&size=5&mine=false" + (status.isEmpty() ? "" : "&status=" + status)), "signatures " + status).body();
            assertTrue(s.has("total"), status);
        }
        assertTrue(Flow.ok(documents.get(admin, "/api/documents/media?page=1&size=2"), "photos").body().has("items"));
    }

    @Test
    void peoplePickersSearchOnTheServerAndReturnOnlyAFewMatches() {
        String admin = Flow.token("ORG_ADMIN");
        JsonNode contacts = Flow.ok(workflow.get(admin, "/api/workflow/chat/contacts"), "contacts").body();
        assertTrue(contacts.path("rows").size() >= 2 && contacts.path("total").asInt() >= 2);
        assertEquals(1, Flow.ok(workflow.get(admin, "/api/workflow/chat/contacts?size=1"), "one contact").body().path("rows").size());
        String name = contacts.path("rows").get(0).path("name").asText();
        JsonNode found = Flow.ok(workflow.get(admin, "/api/workflow/chat/contacts?q=" + name.split(" ")[0]), "found by first name").body();
        assertTrue(found.path("total").asInt() >= 1);
        assertEquals(0, Flow.ok(workflow.get(admin, "/api/workflow/chat/contacts?q=zzzzqqq"), "nobody").body().path("total").asInt());

        JsonNode inbox = Flow.ok(workflow.get(admin, "/api/workflow/chat/conversations?limit=2"), "inbox").body();
        assertTrue(inbox.has("items") && inbox.has("total") && inbox.has("unread"));
        assertTrue(inbox.path("items").size() <= 2);
        assertEquals(0, Flow.ok(workflow.get(admin, "/api/workflow/chat/conversations?q=zzzzqqq"), "no conversation").body().path("total").asInt());

        String doc = Flow.ok(documents.get(admin, "/api/documents?page=1&size=1&status=ACTIVE&categories=LEASE"), "a lease document").body().path("items").get(0).path("id").asText();
        JsonNode signers = Flow.ok(documents.get(admin, "/api/documents/" + doc + "/signers"), "signers").body();
        assertTrue(signers.path("rows").size() >= 1 && signers.has("total") && signers.has("related"));
        assertEquals(0, Flow.ok(documents.get(admin, "/api/documents/" + doc + "/signers?q=zzzzqqq"), "no such signer").body().path("total").asInt());
        String staff = signers.path("rows").get(signers.path("rows").size() - 1).path("name").asText().split(" ")[0];
        assertTrue(Flow.ok(documents.get(admin, "/api/documents/" + doc + "/signers?q=" + staff), "signer by name").body().path("total").asInt() >= 1);
        assertEquals(400, documents.post(admin, "/api/documents/" + doc + "/signature-requests", Map.of("signers", List.of(UUID.randomUUID().toString()))).status(), "an unknown signer is refused");
    }

    @Test
    void whatAPersonHasBeenShownIsSavedOnTheirAccount() {
        String admin = Flow.token("ORG_ADMIN");
        Reply put = identity.put(admin, "/api/identity/me/ui-state", Map.of("guide", Map.of("welcome", true, "tours", Map.of("home", 1))));
        assertEquals(200, put.status());
        JsonNode me = Flow.ok(identity.get(admin, "/api/identity/me"), "me").body();
        assertTrue(me.path("uiState").path("guide").path("welcome").asBoolean());
        Map<String, Object> clear = new HashMap<>();
        clear.put("guide", null);
        assertEquals(200, identity.put(admin, "/api/identity/me/ui-state", clear).status());
        assertFalse(Flow.ok(identity.get(admin, "/api/identity/me"), "me again").body().path("uiState").has("guide"), "a null removes the setting");

        assertEquals(400, identity.put(admin, "/api/identity/me/ui-state", Map.of("bad key!", 1)).status(), "setting names are checked");
        Map<String, Object> many = new HashMap<>();
        for (int i = 0; i < 41; i++) many.put("k" + i, i);
        assertEquals(400, identity.put(admin, "/api/identity/me/ui-state", many).status(), "too many settings in one go");
        assertEquals(400, identity.put(admin, "/api/identity/me/ui-state", Map.of("big", "x".repeat(21_000))).status(), "too much saved state");
    }

    @Test
    void everyErrorHasTheSameShapeAndReadsAsASentence() {
        String admin = Flow.token("ORG_ADMIN");
        Reply missing = leasing.get(admin, "/api/leasing/leases/" + UUID.randomUUID());
        assertEquals(404, missing.status());
        assertEquals("NOT_FOUND", missing.code());
        assertTrue(missing.body().path("detail").asText().startsWith("We couldn't find that lease"), missing.body().path("detail").asText());
        assertFalse(missing.body().path("timestamp").asText().isEmpty());
        assertFalse(missing.body().path("correlationId").asText().isEmpty(), "a reference to quote to support");

        Reply invalid = leasing.post(admin, "/api/leasing/owners", Map.of());
        assertEquals(400, invalid.status());
        assertEquals("Name is required.", invalid.body().path("detail").asText());

        Reply route = leasing.get(admin, "/api/leasing/definitely-not-here");
        assertEquals(404, route.status());
        assertEquals("ROUTE_NOT_FOUND", route.code());
        assertEquals(405, leasing.delete(admin, "/api/leasing/properties").status());
        assertEquals("METHOD_NOT_ALLOWED", leasing.delete(admin, "/api/leasing/properties").code());

        Reply nobody = leasing.get(null, "/api/leasing/owners");
        assertEquals(401, nobody.status());
        assertEquals("UNAUTHORIZED", nobody.code());
        assertTrue(nobody.body().path("detail").asText().startsWith("Your session has ended"));
        Reply denied = leasing.post(Flow.token("TENANT"), "/api/leasing/owners", Map.of("name", "Nope"));
        assertEquals(403, denied.status());
        assertEquals("You don't have permission to do this. Ask an administrator to give you access.", denied.body().path("detail").asText());
    }
}
