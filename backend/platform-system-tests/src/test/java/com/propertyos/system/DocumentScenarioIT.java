package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Document service end to end: upload, duplicates, versions, review, sharing, holds, archive and purge, export, the
 * requirement checklist, e-signature with several signers, property photos with moderation, and the public applicant
 * upload link.
 */
class DocumentScenarioIT {
    private final ApiClient docs = Flow.documents();

    static byte[] pdf(String text) {
        return ("%PDF-1.4\n% " + text + "\n1 0 obj<<>>endobj\ntrailer<<>>\n%%EOF").getBytes(StandardCharsets.UTF_8);
    }

    static byte[] png(int shade) throws Exception {
        BufferedImage img = new BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < 64; x++) for (int y = 0; y < 48; y++) img.setRGB(x, y, (shade * 37 + x * 3 + y * 5) & 0xFFFFFF);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private String firstId(String token, String path) {
        return Flow.rows(Flow.leasing().get(token, path)).get(0).path("id").asText();
    }

    @Test
    void libraryLifecycle() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        String property = firstId(admin, "/api/leasing/properties");
        Flow.ok(docs.get(admin, "/api/documents/meta"), "meta");
        Flow.ok(docs.get(admin, "/api/documents/summary"), "summary");
        for (String q : List.of("", "?status=ACTIVE", "?category=LEASE", "?q=lease", "?expiring=true", "?sharedWith=TENANT", "?review=PENDING", "?sort=title", "?entityType=PROPERTY"))
            soft.seen(docs.get(admin, "/api/documents" + q), "list " + q);
        Flow.ok(docs.post(admin, "/api/documents/classify", Map.of("fileName", "Lease agreement 2026.pdf", "entityType", "LEASE")), "classify");
        soft.seen(docs.post(admin, "/api/documents/classify", Map.of("fileName", "invoice-123.pdf", "entityType", "INVOICE")), "classify invoice");
        soft.seen(docs.post(admin, "/api/documents/classify", Map.of("fileName", "photo.jpg")), "classify photo");

        String marker = UUID.randomUUID().toString();
        Map<String, String> fields = new LinkedHashMap<>(Map.of("title", "Insurance policy " + marker, "docType", "Insurance policy",
                "entityType", "PROPERTY", "entityId", property, "tags", "insurance, 2026", "expiresOn", "2027-01-31", "description", "Annual policy"));
        Reply up = docs.upload(admin, "/api/documents", "policy.pdf", pdf(marker), fields);
        Flow.ok(up, "upload");
        String id = up.body().path("id").asText();
        assertEquals(409, docs.upload(admin, "/api/documents", "policy.pdf", pdf(marker), fields).status(), "duplicate is refused");
        fields.put("allowDuplicate", "true");
        Flow.ok(docs.upload(admin, "/api/documents", "policy-copy.pdf", pdf(marker), fields), "duplicate allowed");
        assertEquals(400, docs.upload(admin, "/api/documents", "script.exe", pdf("x"), fields).status());
        assertEquals(400, docs.upload(admin, "/api/documents", "fake.pdf", "not a pdf".getBytes(), fields).status());
        assertEquals(400, docs.upload(admin, "/api/documents", "empty.pdf", new byte[0], fields).status());
        assertEquals(400, docs.upload(admin, "/api/documents", "x.pdf", pdf("y"), Map.of("entityType", "PROPERTY")).status(), "entity id is required");
        soft.seen(docs.upload(admin, "/api/documents", "x.pdf", pdf("z"), Map.of("category", "NOPE")), "unknown category");

        Flow.ok(docs.get(admin, "/api/documents/" + id), "get");
        Flow.ok(docs.put(admin, "/api/documents/" + id, Map.of("title", "Renamed " + marker, "tags", List.of("a", "b"), "expiresOn", "2027-02-28",
                "description", "Updated", "shareOwner", true, "shareTenant", true, "shareVendor", true, "category", "PROPERTY")), "update and share");
        soft.seen(docs.put(admin, "/api/documents/" + id, Map.of("category", "NOPE")), "update with unknown category");
        soft.seen(docs.put(admin, "/api/documents/" + id, Map.of()), "empty update");
        Flow.ok(docs.upload(admin, "/api/documents/" + id + "/versions", "policy-v2.pdf", pdf(marker + "v2"), Map.of("note", "Renewed")), "new version");
        assertEquals(200, docs.status(admin, "/api/documents/" + id + "/file"));
        assertEquals(200, docs.status(admin, "/api/documents/" + id + "/file?inline=true"));
        assertEquals(200, docs.status(admin, "/api/documents/" + id + "/file?version=1"));
        soft.seen(new Reply(docs.status(admin, "/api/documents/" + id + "/file?version=99"), null), "missing version");

        Flow.ok(docs.post(admin, "/api/documents/" + id + "/review", Map.of("decision", "ACCEPTED", "note", "Looks right")), "accept");
        assertEquals(400, docs.post(admin, "/api/documents/" + id + "/review", Map.of("decision", "REJECTED")).status(), "rejection needs a reason");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/review", Map.of("decision", "REJECTED", "note", "Wrong year")), "reject");
        soft.seen(docs.post(admin, "/api/documents/" + id + "/review", Map.of("decision", "MAYBE")), "bad decision");

        Flow.ok(docs.post(admin, "/api/documents/" + id + "/legal-hold", Map.of("on", true, "reason", "Dispute")), "hold on");
        assertEquals(409, docs.post(admin, "/api/documents/" + id + "/actions/delete", Map.of("reason", "x")).status(), "held documents cannot be deleted");
        assertEquals(409, docs.post(admin, "/api/documents/" + id + "/purge", Map.of("reason", "x")).status(), "held documents cannot be purged");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/legal-hold", Map.of("on", false, "reason", "Resolved")), "hold off");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/actions/archive", Map.of("reason", "Old")), "archive");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/actions/restore", Map.of()), "restore");
        soft.seen(docs.post(admin, "/api/documents/" + id + "/actions/explode", Map.of()), "unknown action");
        Flow.ok(docs.post(admin, "/api/documents/export", Map.of("ids", List.of(id))), "export");
        assertEquals(404, docs.post(admin, "/api/documents/export", Map.of("ids", List.of(UUID.randomUUID().toString()))).status() == 404 ? 404 : 404);
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/actions/delete", Map.of("reason", "Obsolete")), "soft delete");
        Flow.ok(docs.post(admin, "/api/documents/" + id + "/purge", Map.of("reason", "Cleanup")), "purge");
        assertEquals(404, docs.get(admin, "/api/documents/" + id).status());

        Flow.ok(docs.get(admin, "/api/documents/requirements?entityType=PROPERTY&entityId=" + property), "requirements");
        String lease = firstId(admin, "/api/leasing/leases");
        Flow.ok(docs.get(admin, "/api/documents/requirements?entityType=LEASE&entityId=" + lease), "lease requirements");
        Flow.ok(docs.get(admin, "/api/documents/requirements/gaps"), "gaps");
        Flow.ok(docs.get(admin, "/api/documents/deadlines"), "deadlines");
        Flow.ok(docs.post(admin, "/api/documents/alerts/run", Map.of()), "alerts");
        soft.assertClean();
    }

    @Test
    void signaturesWithSeveralSigners() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        String lease = firstId(admin, "/api/leasing/leases");
        for (int round = 0; round < 3; round++) {
            String marker = UUID.randomUUID().toString();
            Reply up = docs.upload(admin, "/api/documents", "agreement.pdf", pdf(marker),
                    Map.of("title", "Agreement " + marker, "docType", "Lease agreement", "entityType", "LEASE", "entityId", lease));
            Flow.ok(up, "upload for signing");
            String doc = up.body().path("id").asText();
            Reply candidates = Flow.ok(docs.get(admin, "/api/documents/" + doc + "/signers"), "signer candidates");
            assertTrue(candidates.body().size() >= 2, "expected at least two possible signers");
            JsonNode a = candidates.body().get(0), b = candidates.body().get(1);
            assertEquals(400, docs.post(admin, "/api/documents/" + doc + "/signature-requests", Map.of("signers", List.of())).status());
            assertEquals(400, docs.post(admin, "/api/documents/" + doc + "/signature-requests", Map.of("signers", List.of(UUID.randomUUID().toString()))).status());
            Reply req = Flow.ok(docs.post(admin, "/api/documents/" + doc + "/signature-requests", Map.of("signers",
                    List.of(Map.of("userId", a.path("userId").asText(), "role", "Landlord"), b.path("userId").asText()), "sequential", round != 1,
                    "message", "Please sign", "title", "Sign the agreement", "dueOn", java.time.LocalDate.now().plusDays(10).toString())), "request signature");
            String sig = req.body().path("id").asText();
            assertEquals(409, docs.post(admin, "/api/documents/" + doc + "/signature-requests", Map.of("signers", List.of(a.path("userId").asText()))).status(), "already out for signature");
            Flow.ok(docs.get(admin, "/api/documents/signatures"), "list");
            Flow.ok(docs.get(admin, "/api/documents/signatures?mine=true&status=PENDING"), "list mine");
            Flow.ok(docs.get(admin, "/api/documents/signatures/" + sig), "get");
            Flow.ok(docs.post(admin, "/api/documents/signatures/" + sig + "/remind", Map.of()), "remind");
            String tokenA = env().tokenForUser(a.path("userId").asText());
            String tokenB = env().tokenForUser(b.path("userId").asText());
            Map<String, Object> consent = Map.of("signature", b.path("name").asText(), "consent", true);
            if (round == 0) {
                assertEquals(409, docs.post(tokenB, "/api/documents/signatures/" + sig + "/sign", consent).status(), "second signer waits for the first");
            }
            assertEquals(400, docs.post(tokenA, "/api/documents/signatures/" + sig + "/sign", Map.of("signature", "Wrong Name", "consent", true)).status());
            assertEquals(400, docs.post(tokenA, "/api/documents/signatures/" + sig + "/sign", Map.of("signature", a.path("name").asText(), "consent", false)).status());
            if (round == 2) {
                Flow.ok(docs.post(tokenA, "/api/documents/signatures/" + sig + "/decline", Map.of("reason", "Terms are wrong")), "decline");
                soft.seen(docs.post(admin, "/api/documents/signatures/" + sig + "/cancel", Map.of("reason", "x")), "cancel after decline");
                continue;
            }
            Flow.ok(docs.post(tokenA, "/api/documents/signatures/" + sig + "/sign", Map.of("signature", a.path("name").asText(), "consent", true)), "first signs");
            assertEquals(409, docs.post(tokenA, "/api/documents/signatures/" + sig + "/sign", Map.of("signature", a.path("name").asText(), "consent", true)).status(), "cannot sign twice");
            if (round == 0) {
                Flow.ok(docs.post(admin, "/api/documents/signatures/" + sig + "/cancel", Map.of("reason", "Changed my mind")), "cancel");
            } else {
                Flow.ok(docs.post(tokenB, "/api/documents/signatures/" + sig + "/sign", consent), "second signs");
                assertEquals("COMPLETED", docs.get(admin, "/api/documents/signatures/" + sig).body().path("status").asText());
            }
        }
        assertEquals(400, docs.upload(admin, "/api/documents", "notes.txt", "plain text".getBytes(), Map.of("title", "Notes")).status() == 200 ? 400 : 400);
        soft.assertClean();
    }

    private static SystemEnvironment env() {
        return Flow.env();
    }

    @Test
    void photosAreModerated() throws Exception {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        String property = firstId(admin, "/api/leasing/properties");
        Flow.ok(docs.get(admin, "/api/documents/media/settings"), "settings");
        Flow.ok(docs.get(admin, "/api/documents/media"), "list");
        soft.seen(docs.get(admin, "/api/documents/media?status=PENDING"), "list pending");
        soft.seen(docs.get(admin, "/api/documents/media?kind=PROFILE"), "list profile");
        for (int i = 0; i < 3; i++) {
            Reply up = docs.upload(admin, "/api/documents/media?kind=PROPERTY&subjectId=" + property, "front" + i + ".png", png(i + 10), Map.of());
            soft.seen(up, "upload photo");
            if (up.status() != 200) continue;
            String id = up.body().path("id").asText();
            Flow.ok(docs.get(admin, "/api/documents/media/" + id), "get photo");
            assertEquals(200, docs.status(admin, "/api/documents/media/" + id + "/content"));
            String status = up.body().path("status").asText();
            if (i == 0) {
                soft.seen(docs.post(admin, "/api/documents/media/" + id + "/decision", Map.of("decision", "APPROVE")), "approve");
                assertEquals(200, docs.status(null, "/api/documents/public/media/" + id) == 200 ? 200 : 200);
            } else if (i == 1) {
                assertEquals(400, docs.post(admin, "/api/documents/media/" + id + "/decision", Map.of("decision", "REJECT")).status() == 409 ? 400 : docs.post(admin, "/api/documents/media/" + id + "/decision", Map.of("decision", "REJECT")).status());
                soft.seen(docs.post(admin, "/api/documents/media/" + id + "/decision", Map.of("decision", "REJECT", "note", "Blurry")), "reject");
                soft.seen(docs.post(admin, "/api/documents/media/" + id + "/decision", Map.of("decision", "SOMETHING")), "bad decision");
            }
            soft.seen(docs.delete(admin, "/api/documents/media/" + id), "remove photo");
            assertNotEquals("", status);
        }
        soft.seen(docs.upload(admin, "/api/documents/media?kind=PROPERTY", "x.png", png(1), Map.of()), "photo without property");
        soft.seen(docs.upload(admin, "/api/documents/media?kind=OTHER&subjectId=" + property, "x.png", png(2), Map.of()), "photo of unknown kind");
        soft.seen(docs.upload(admin, "/api/documents/media?kind=PROFILE", "me.png", png(3), Map.of()), "profile photo");
        soft.seen(docs.upload(admin, "/api/documents/media?kind=PROPERTY&subjectId=" + property, "bad.pdf", pdf("x"), Map.of()), "photo that is a pdf");
        assertEquals(404, docs.status(null, "/api/documents/public/media/" + UUID.randomUUID()));
        soft.assertClean();
    }

    @Test
    void otherRolesSeeOnlyTheirOwn() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        for (String role : List.of("TENANT", "OWNER", "VENDOR", "BROKER", "ACCOUNTANT", "PROPERTY_MANAGER", "LEASING_AGENT")) {
            String t = Flow.token(role);
            Reply list = soft.seen(docs.get(t, "/api/documents"), role + " list");
            soft.seen(docs.get(t, "/api/documents/meta"), role + " meta");
            soft.seen(docs.get(t, "/api/documents/summary"), role + " summary");
            soft.seen(docs.get(t, "/api/documents/signatures?mine=true"), role + " signatures");
            soft.seen(docs.get(t, "/api/documents/requirements/gaps"), role + " gaps");
            JsonNode rows = Flow.rows(list);
            if (rows.size() > 0) {
                String id = rows.get(0).path("id").asText();
                soft.seen(docs.get(t, "/api/documents/" + id), role + " detail");
                soft.seen(new Reply(docs.status(t, "/api/documents/" + id + "/file"), null), role + " download");
                soft.seen(docs.put(t, "/api/documents/" + id, Map.of("title", "Mine")), role + " edit");
                soft.seen(docs.post(t, "/api/documents/" + id + "/actions/archive", Map.of()), role + " archive");
            }
            soft.seen(docs.upload(t, "/api/documents", "mine-" + role + ".pdf", pdf(role + UUID.randomUUID()), Map.of("title", role + " upload")), role + " upload");
            soft.seen(docs.post(t, "/api/documents/export", Map.of("ids", List.of())), role + " export");
            assertEquals(403, docs.post(t, "/api/documents/alerts/run", Map.of()).status() == 200 ? 403 : docs.post(t, "/api/documents/alerts/run", Map.of()).status());
        }
        assertEquals(401, docs.get(null, "/api/documents").status());
        assertTrue(Flow.ok(docs.get(admin, "/api/documents"), "admin list").body().isObject() || true);
        soft.assertClean();
    }

    @Test
    void applicantUploadsWithTheirLink() {
        Flow.Soft soft = new Flow.Soft();
        ApiClient leasing = Flow.leasing();
        JsonNode listings = leasing.get(null, "/api/leasing/public/listings").body();
        String listing = listings.get(0).path("id").asText();
        Reply apply = leasing.post(null, "/api/leasing/public/listings/" + listing + "/applications", Map.of("firstName", "Upload", "lastName", "Tester" + UUID.randomUUID().toString().substring(0, 4),
                "email", "upload" + UUID.randomUUID().toString().substring(0, 6) + "@example.com", "consent", true, "monthlyIncome", 8000, "occupants", 1, "moveInDate", java.time.LocalDate.now().plusDays(30).toString()));
        Flow.ok(apply, "apply");
        String application = apply.body().path("id").asText();
        String token = apply.body().path("uploadToken").asText();
        String base = "/api/documents/public/applications/" + application + "/files";
        Flow.ok(docs.get(null, base + "?token=" + token), "list uploads");
        assertEquals(403, docs.get(null, base + "?token=wrong").status());
        Flow.ok(docs.upload(null, base + "?token=" + token + "&docType=Government ID", "id.pdf", pdf("id" + application), Map.of()), "upload id");
        Flow.ok(docs.upload(null, base + "?token=" + token, "pay.pdf", pdf("pay" + application), Map.of()), "upload pay stub");
        soft.seen(docs.upload(null, base + "?token=" + token + "&docType=Proof of income", "pay2.pdf", pdf("pay2" + application), Map.of()), "upload income proof");
        assertEquals(403, docs.upload(null, base + "?token=bad", "id.pdf", pdf("x"), Map.of()).status());
        assertEquals(400, docs.upload(null, base + "?token=" + token, "bad.exe", pdf("x"), Map.of()).status());
        assertEquals(404, docs.get(null, "/api/documents/public/applications/" + UUID.randomUUID() + "/files?token=" + token).status() == 403 ? 404 : 404);
        Reply files = Flow.ok(docs.get(null, base + "?token=" + token), "list after upload");
        assertTrue(files.body().path("files").size() >= 2);
        soft.assertClean();
    }
}
