package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Marketing campaigns (signed inbound posts, tracking links), paid listing boosts with the operator's pricing, signed webhooks, and the email sent when staff
 * answer a lead. Each test goes through the real services the way a person or another system would.
 */
class NewFeaturesIT {
    private final ApiClient leasing = Flow.leasing();
    private final ApiClient identity = Flow.identity();
    private final ApiClient workflow = Flow.workflow();

    private static String sign(String secret, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static <T> T eventually(String what, Supplier<T> check, java.util.function.Predicate<T> done) throws Exception {
        long end = System.currentTimeMillis() + 60_000;
        T last = check.get();
        while (!done.test(last) && System.currentTimeMillis() < end) {
            Thread.sleep(1000);
            last = check.get();
        }
        assertTrue(done.test(last), "Timed out waiting for " + what);
        return last;
    }

    private JsonNode liveListings(String admin) {
        return Flow.rows(Flow.ok(leasing.get(admin, "/api/leasing/listings?status=LIVE"), "live listings"));
    }

    // ----------------------------------------------------------------------------------------------- campaigns

    @Test
    void campaignsTakeSignedLeadsAndApplicationsAndCountThemPerCampaign() throws Exception {
        String admin = Flow.token("ORG_ADMIN"), tenant = Flow.token("TENANT");
        JsonNode live = liveListings(admin);
        assertTrue(live.size() >= 2, "the demo company has live homes");
        String listing = live.get(0).path("id").asText(), listing2 = live.get(1).path("id").asText();
        String name = "System campaign " + UUID.randomUUID().toString().substring(0, 6);

        assertEquals(403, leasing.post(tenant, "/api/leasing/campaigns", Map.of("name", name, "channel", "EMAIL")).status(), "residents cannot start campaigns");
        assertEquals(401, leasing.get(null, "/api/leasing/campaigns").status());
        assertEquals(400, leasing.post(admin, "/api/leasing/campaigns", Map.of("name", name, "channel", "TWITTER")).status(), "unknown channel");
        assertEquals(400, leasing.post(admin, "/api/leasing/campaigns", Map.of("name", "", "channel", "EMAIL")).status(), "name needed");

        JsonNode c = Flow.ok(leasing.post(admin, "/api/leasing/campaigns", Flow.map("name", name, "channel", "FACEBOOK_ADS", "spend", 500)), "create campaign").body();
        String id = c.path("id").asText(), key = c.path("public_key").asText(), secret = c.path("secret").asText(), tracking = c.path("tracking_code").asText();
        assertTrue(secret.startsWith("whsec_"), "the secret is shown once");
        assertFalse(Flow.ok(leasing.get(admin, "/api/leasing/campaigns"), "list").body().toString().contains("whsec_"), "the list never shows a secret");
        Flow.ok(leasing.get(admin, "/api/leasing/campaigns/" + id), "detail");
        Flow.ok(leasing.get(admin, "/api/leasing/campaigns/series"), "chart");
        Flow.ok(leasing.put(admin, "/api/leasing/campaigns/" + id, Map.of("name", name + " renamed", "spend", 650)), "edit");

        String path = "/api/leasing/public/inbound/" + key;
        String lead = "{\"type\":\"lead\",\"name\":\"Dana Prospect\",\"email\":\"dana." + id.substring(0, 6) + "@example.com\",\"phone\":\"5125550101\",\"listingId\":\"" + listing + "\",\"message\":\"Is it available? I would like a tour.\"}";
        Map<String, String> good = Map.of("X-Signature", "sha256=" + sign(secret, lead));
        assertEquals(200, leasing.postRaw(path, lead, good).status(), "a signed lead is accepted");
        assertEquals(200, leasing.postRaw(path, lead, good).status(), "the same person again is not counted twice");
        assertTrue(leasing.postRaw(path, lead, Map.of("X-Signature", "sha256=" + sign("wrong", lead))).status() >= 400, "wrong signature");
        assertTrue(leasing.postRaw(path, lead, Map.of()).status() >= 400, "no signature");
        assertTrue(leasing.postRaw(path, lead.replace("Dana", "Eve"), good).status() >= 400, "tampered body");
        assertTrue(leasing.postRaw("/api/leasing/public/inbound/cmp_unknown", lead, good).status() >= 400, "unknown key");
        String noHome = "{\"type\":\"lead\",\"name\":\"Dana\",\"email\":\"d3@example.com\"}";
        assertTrue(leasing.postRaw(path, noHome, Map.of("X-Signature", "sha256=" + sign(secret, noHome))).status() >= 400, "no home given");
        String badEmail = "{\"type\":\"lead\",\"name\":\"Dana\",\"email\":\"nope\",\"listingId\":\"" + listing + "\"}";
        assertTrue(leasing.postRaw(path, badEmail, Map.of("X-Signature", "sha256=" + sign(secret, badEmail))).status() >= 400, "bad email");
        String app = "{\"type\":\"application\",\"name\":\"Ann Applicant\",\"email\":\"ann." + id.substring(0, 6) + "@example.com\",\"listingId\":\"" + listing + "\",\"consent\":true,\"monthlyIncome\":7000}";
        assertEquals(200, leasing.postRaw(path, app, Map.of("X-Signature", "sha256=" + sign(secret, app))).status(), "a signed application");
        String noConsent = app.replace("\"consent\":true", "\"consent\":false").replace("ann.", "bob.");
        assertTrue(leasing.postRaw(path, noConsent, Map.of("X-Signature", "sha256=" + sign(secret, noConsent))).status() >= 400, "no consent");

        // A website enquiry that carries the tracking code counts for the campaign too.
        Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing2 + "/enquiries",
                Flow.map("name", "Web Visitor", "email", "web." + id.substring(0, 6) + "@example.com", "message", "Hello", "campaign", tracking)), "tracked enquiry");
        JsonNode detail = Flow.ok(leasing.get(admin, "/api/leasing/campaigns/" + id), "detail again").body();
        assertTrue(detail.toString().contains("Dana Prospect") || detail.path("leads").asInt(detail.path("lead_count").asInt(0)) >= 1, "the lead is counted: " + detail);

        Flow.ok(leasing.post(admin, "/api/leasing/campaigns/" + id + "/test-lead", Map.of()), "test lead");
        assertEquals("PAUSED", Flow.ok(leasing.post(admin, "/api/leasing/campaigns/" + id + "/pause", Map.of()), "pause").body().path("state").asText());
        assertTrue(leasing.postRaw(path, lead.replace("Dana", "Late").replace("dana.", "late."), Map.of("X-Signature", "sha256=" + sign(secret, lead.replace("Dana", "Late").replace("dana.", "late.")))).status() >= 400, "paused campaigns take nothing");
        Flow.ok(leasing.post(admin, "/api/leasing/campaigns/" + id + "/resume", Map.of()), "resume");
        String fresh = Flow.ok(leasing.post(admin, "/api/leasing/campaigns/" + id + "/rotate-key", Map.of()), "rotate").body().path("secret").asText();
        assertTrue(fresh.startsWith("whsec_") && !fresh.equals(secret), "a new secret");
        assertTrue(leasing.postRaw(path, lead, good).status() >= 400, "the old secret stops working");
        assertEquals(404, leasing.get(admin, "/api/leasing/campaigns/" + UUID.randomUUID()).status());
    }

    // ----------------------------------------------------------------------------------------------- boosts

    @Test
    void aBoostIsBoughtPaidAndManagedByTheOperator() throws Exception {
        String admin = Flow.token("ORG_ADMIN"), tenant = Flow.token("TENANT"), platform = Flow.token("PLATFORM_ADMIN");
        JsonNode live = liveListings(admin);
        String listing = live.get(0).path("id").asText();

        Flow.ok(leasing.get(admin, "/api/leasing/boosts/options"), "options");
        Flow.ok(leasing.get(admin, "/api/leasing/boosts/quote?days=7"), "quote");
        assertEquals(400, leasing.post(admin, "/api/leasing/listings/" + listing + "/boosts", Map.of("days", 5)).status(), "a period that is not on offer");
        assertEquals(403, leasing.post(tenant, "/api/leasing/listings/" + listing + "/boosts", Map.of("days", 7)).status(), "residents cannot buy");

        // The operator agrees a price and a promo code with the company first.
        JsonNode orgs = Flow.rows(Flow.ok(identity.get(platform, "/api/identity/platform/orgs"), "companies"));
        String org = null;
        for (JsonNode o : orgs) if (o.path("name").asText().contains("Summit")) org = o.path("id").asText();
        assertNotNull(org, "the demo company is listed");
        Flow.ok(leasing.put(platform, "/api/leasing/platform/boosts/prices", Flow.map("orgId", org, "days", 14, "price", "75.00", "note", "agreed in a call")), "agreed price");
        assertTrue(Flow.ok(leasing.get(platform, "/api/leasing/platform/boosts/prices"), "prices").body().toString().contains("75"));
        String code = "SYS" + UUID.randomUUID().toString().substring(0, 5).toUpperCase();
        Flow.ok(leasing.post(platform, "/api/leasing/platform/boosts/promos", Flow.map("code", code, "percentOff", 20, "maxUses", 5, "note", "system test")), "promo");
        assertEquals(400, leasing.post(platform, "/api/leasing/platform/boosts/promos", Flow.map("code", "BAD" + code, "percentOff", 20, "amountOff", 5)).status(), "one kind of discount only");
        Flow.ok(leasing.get(admin, "/api/leasing/boosts/quote?days=7&code=" + code), "quote with the code");

        Reply bought = Flow.ok(leasing.post(admin, "/api/leasing/listings/" + listing + "/boosts", Map.of("days", 7, "promoCode", code)), "buy a boost");
        String invoice = bought.body().path("invoice_id").asText();
        assertFalse(invoice.isBlank(), "an invoice is raised");
        assertEquals(409, leasing.post(admin, "/api/leasing/listings/" + listing + "/boosts", Map.of("days", 14)).status(), "one boost at a time for a listing");
        assertEquals("PENDING", Flow.ok(leasing.get(admin, "/api/leasing/listings/" + listing + "/boosts"), "boosts").body().get(0).path("status").asText());

        assertTrue(identity.post(admin, "/api/identity/billing/invoices/" + invoice + "/pay", Map.of("method", "CARD", "last4", "0002")).status() >= 400, "a declined card");
        Flow.ok(identity.post(admin, "/api/identity/billing/invoices/" + invoice + "/pay", Map.of("method", "CARD", "last4", "4242")), "paid");
        eventually("the boost to start", () -> leasing.get(admin, "/api/leasing/listings/" + listing + "/boosts").body().get(0).path("status").asText(), "ACTIVE"::equals);
        assertTrue(Flow.ok(leasing.get(null, "/api/leasing/public/listings/" + listing), "public page").body().path("sponsored").asBoolean(), "the listing is labelled Sponsored");
        Flow.ok(leasing.get(admin, "/api/leasing/listings/" + listing + "/ranking"), "why it is ranked here");
        Flow.ok(leasing.get(admin, "/api/leasing/listings/ranking"), "company ranking");
        Flow.ok(leasing.get(null, "/api/leasing/public/listings?page=1&size=10&sort=score&dir=desc"), "public search");
        Flow.ok(leasing.get(null, "/api/leasing/public/listings?sort=rating_rank&dir=desc"), "top rated");

        // Operator views, a free grant on another listing, and ending boosts.
        Flow.ok(leasing.get(platform, "/api/leasing/platform/boosts/overview"), "overview");
        Flow.ok(leasing.get(platform, "/api/leasing/platform/boosts"), "all boosts");
        Flow.ok(leasing.get(platform, "/api/leasing/platform/boosts/eligible?q=a"), "eligible listings");
        String other = live.get(1).path("id").asText();
        assertEquals(400, leasing.post(platform, "/api/leasing/platform/boosts/grant", Flow.map("listingId", other, "days", 0, "reason", "x")).status(), "days must be 1 to 90");
        Reply granted = leasing.post(platform, "/api/leasing/platform/boosts/grant", Flow.map("listingId", other, "days", 3, "reason", "launch gift"));
        assertTrue(granted.status() == 200 || granted.status() == 409, "a grant is made, or the listing already has a boost: " + granted.body());
        JsonNode boosts = Flow.rows(Flow.ok(leasing.get(platform, "/api/leasing/platform/boosts"), "all boosts again"));
        for (JsonNode b : boosts) {
            if (listing.equals(b.path("listing_id").asText()) || other.equals(b.path("listing_id").asText()))
                Flow.ok(leasing.post(platform, "/api/leasing/platform/boosts/" + b.path("id").asText() + "/cancel", Map.of("reason", "system test over")), "cancel");
        }
        Flow.ok(leasing.put(platform, "/api/leasing/platform/boosts/promos/" + code, Map.of("active", false)), "promo off");
        Flow.ok(leasing.delete(platform, "/api/leasing/platform/boosts/prices?orgId=" + org + "&days=14"), "back to the standard price");
        assertEquals(403, leasing.get(admin, "/api/leasing/platform/boosts/overview").status(), "companies cannot see the operator's view");
    }

    // ----------------------------------------------------------------------------------------------- webhooks

    @Test
    void webhooksAreSignedDeliveredLoggedAndRetried() throws Exception {
        String admin = Flow.token("ORG_ADMIN"), tenant = Flow.token("TENANT");
        List<String> received = new CopyOnWriteArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", x -> {
            received.add(x.getRequestHeaders().entrySet().toString() + new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            x.sendResponseHeaders(200, -1);
            x.close();
        });
        server.createContext("/fail", x -> {
            x.getRequestBody().readAllBytes();
            x.sendResponseHeaders(500, -1);
            x.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            JsonNode events = Flow.ok(workflow.get(admin, "/api/workflow/webhooks/events"), "events").body();
            assertTrue(events.size() >= 3, "the catalogue lists the events");
            List<String> keys = List.of(events.get(0).path("key").asText(), events.get(1).path("key").asText());

            assertEquals(403, workflow.post(tenant, "/api/workflow/webhooks", Flow.map("name", "x", "url", base + "/ok", "events", keys)).status());
            assertEquals(400, workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "x", "url", base + "/ok", "events", List.of())).status(), "no events");
            assertEquals(400, workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "x", "url", base + "/ok", "events", List.of("nope"))).status(), "unknown event");
            assertEquals(400, workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "", "url", base + "/ok", "events", keys)).status(), "no name");
            assertEquals(400, workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "x", "url", "ftp://example.com/x", "events", keys)).status(), "not a web address");
            assertEquals(400, workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "x", "url", "http://169.254.169.254/latest", "events", keys)).status(), "the cloud metadata address");

            JsonNode good = Flow.ok(workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "System CRM", "url", base + "/ok", "events", keys)), "create").body();
            String goodId = good.path("id").asText();
            assertTrue(good.path("secret").asText().startsWith("whsec_"), "the secret is shown once");
            JsonNode bad = Flow.ok(workflow.post(admin, "/api/workflow/webhooks", Flow.map("name", "System failing", "url", base + "/fail", "events", keys)), "create failing").body();
            String badId = bad.path("id").asText();
            assertFalse(Flow.ok(workflow.get(admin, "/api/workflow/webhooks"), "list").body().toString().contains("whsec_"), "the list never shows a secret");

            // A test event is signed, delivered and logged; a receiver that answers 500 is retried later.
            Flow.ok(workflow.post(admin, "/api/workflow/webhooks/" + goodId + "/test", Map.of()), "test delivery");
            assertFalse(received.isEmpty(), "the receiver got the event");
            assertTrue(received.get(0).toLowerCase().contains("signature"), "it carried a signature header");
            Flow.ok(workflow.post(admin, "/api/workflow/webhooks/" + badId + "/test", Map.of()), "test failing delivery");
            JsonNode log = Flow.rows(Flow.ok(workflow.get(admin, "/api/workflow/webhooks/deliveries?endpointId=" + badId), "log of the failing one"));
            assertTrue(log.size() >= 1, "the failed delivery is logged");
            String deliveryId = log.get(0).path("id").asText();
            Flow.ok(workflow.get(admin, "/api/workflow/webhooks/deliveries/" + deliveryId), "one delivery with its exact body");
            Flow.ok(workflow.get(admin, "/api/workflow/webhooks/deliveries?status=SUCCEEDED"), "filter by status");
            Flow.ok(workflow.post(admin, "/api/workflow/webhooks/deliveries/" + deliveryId + "/retry", Map.of()), "retry now");

            Flow.ok(workflow.put(admin, "/api/workflow/webhooks/" + goodId, Map.of("active", false)), "switch off");
            Flow.ok(workflow.put(admin, "/api/workflow/webhooks/" + goodId, Map.of("active", true, "name", "System CRM 2")), "switch on");
            int moved = workflow.put(admin, "/api/workflow/webhooks/" + goodId, Map.of("url", "http://10.0.0.1/x")).status();
            assertTrue(moved == 200 || moved == 400, "a private address is refused, or allowed by the test setting");
            Flow.ok(workflow.put(admin, "/api/workflow/webhooks/" + goodId, Map.of("url", base + "/ok")), "back to the receiver");
            String rotated = Flow.ok(workflow.post(admin, "/api/workflow/webhooks/" + goodId + "/rotate-secret", Map.of()), "rotate").body().path("secret").asText();
            assertTrue(rotated.startsWith("whsec_") && !rotated.equals(good.path("secret").asText()));
            assertEquals(404, workflow.put(admin, "/api/workflow/webhooks/" + UUID.randomUUID(), Map.of("active", false)).status());
            Flow.ok(workflow.delete(admin, "/api/workflow/webhooks/" + goodId), "delete");
            Flow.ok(workflow.delete(admin, "/api/workflow/webhooks/" + badId), "delete failing");
        } finally {
            server.stop(0);
        }
    }

    // ----------------------------------------------------------------------------------------------- lead email

    @Test
    void answeringALeadSendsTheReplyByEmailAndLogsIt() throws Exception {
        String admin = Flow.token("ORG_ADMIN");
        String listing = liveListings(admin).get(0).path("id").asText();
        String email = "tessa." + UUID.randomUUID().toString().substring(0, 6) + "@example.com";
        Reply enquiry = Flow.ok(leasing.post(null, "/api/leasing/public/listings/" + listing + "/enquiries",
                Flow.map("name", "Tessa Nguyen", "email", email, "message", "I toured yesterday and love it. How do I apply?")), "enquiry");
        String lead = enquiry.body().path("id").asText();
        Flow.ok(leasing.post(admin, "/api/leasing/leads/" + lead + "/respond", Map.of("reply", "Hi Tessa,\n\nThanks for touring. Apply here today & we will move fast.")), "send reply");

        JsonNode sent = eventually("the reply email", () -> {
            for (JsonNode d : Flow.rows(workflow.get(admin, "/api/workflow/deliveries"))) {
                if ("EMAIL".equals(d.path("channel").asText()) && email.equals(d.path("recipient").asText())) return d;
            }
            return null;
        }, d -> d != null && "SENT".equals(d.path("status").asText()));
        assertTrue(sent.path("subject").asText().startsWith("Re: your enquiry about"), "the subject names the home");
    }
}
