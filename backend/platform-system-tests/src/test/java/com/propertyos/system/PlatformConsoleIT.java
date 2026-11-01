package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The platform operator's console: whether every service is up, how much each company uses the product, and the log of
 * everything sent. All of it is for platform administrators only, and none of it may show one company the data of another.
 */
class PlatformConsoleIT {
    private final ApiClient identity = Flow.identity();
    private final ApiClient workflow = Flow.workflow();

    @Test
    void everyServiceReportsItsHealthToThePlatformAdministratorOnly() {
        String platform = Flow.token("PLATFORM_ADMIN");
        Reply r = Flow.ok(identity.get(platform, "/api/identity/platform/health"), "health");
        JsonNode body = r.body();
        assertEquals("UP", body.path("overall").asText(), "everything is running in the test environment");
        assertEquals(8, body.path("total").asInt(), "seven services and the database");
        assertEquals(8, body.path("up").asInt());
        Set<String> names = new java.util.HashSet<>();
        for (JsonNode s : body.path("services")) {
            names.add(s.path("service").asText());
            assertEquals("UP", s.path("status").asText(), s.path("service").asText());
            assertTrue(s.path("millis").asLong() >= 0);
        }
        assertEquals(Set.of("IDENTITY", "LEASING", "BILLING", "MAINTENANCE", "WORKFLOW", "AI", "DOCUMENTS", "DATABASE"), names);
        assertTrue(body.hasNonNull("checkedAt"));
        for (String role : List.of("ORG_ADMIN", "TENANT", "OWNER"))
            assertEquals(403, identity.get(Flow.token(role), "/api/identity/platform/health").status(), role + " cannot see the platform's health");
        assertEquals(401, identity.get(null, "/api/identity/platform/health").status());
    }

    @Test
    void companyUsageShowsWhoReallyUsesTheProduct() {
        String platform = Flow.token("PLATFORM_ADMIN");
        Reply all = Flow.ok(identity.get(platform, "/api/identity/platform/usage?page=1&size=50"), "usage");
        JsonNode rows = all.body().path("rows");
        assertTrue(rows.size() >= 2, "the demo companies are listed");
        assertTrue(all.body().path("total").asInt() >= rows.size());
        Set<String> engagements = Set.of("ACTIVE", "SLOWING", "QUIET", "NEVER");
        for (JsonNode o : rows) {
            assertTrue(engagements.contains(o.path("engagement").asText()), "engagement of " + o.path("name").asText());
            assertTrue(o.path("users").asInt() >= o.path("active_30d").asInt());
            assertTrue(o.path("active_30d").asInt() >= o.path("active_7d").asInt());
            assertTrue(!"PropertyOS Platform".equals(o.path("name").asText()), "the operator's own company is not a customer");
            if ("NEVER".equals(o.path("engagement").asText())) assertTrue(o.path("last_sign_in").isNull() || o.path("last_sign_in").asText().isEmpty());
        }
        // The company the test just signed in to is active this week.
        boolean someoneActive = false;
        for (JsonNode o : rows) if ("ACTIVE".equals(o.path("engagement").asText())) someoneActive = true;
        assertTrue(someoneActive, "somebody signed in during this run");
        for (String e : engagements) {
            Reply f = Flow.ok(identity.get(platform, "/api/identity/platform/usage?page=1&size=50&f.engagement=" + e), "usage " + e);
            for (JsonNode o : f.body().path("rows")) assertEquals(e, o.path("engagement").asText());
        }
        Reply sorted = Flow.ok(identity.get(platform, "/api/identity/platform/usage?page=1&size=10&sort=users&dir=desc"), "sorted by people");
        int previous = Integer.MAX_VALUE;
        for (JsonNode o : sorted.body().path("rows")) {
            assertTrue(o.path("users").asInt() <= previous, "largest company first");
            previous = o.path("users").asInt();
        }
        Reply searched = Flow.ok(identity.get(platform, "/api/identity/platform/usage?page=1&size=20&q=Summit"), "search");
        assertTrue(searched.body().path("rows").size() >= 1);
        assertEquals(403, identity.get(Flow.token("ORG_ADMIN"), "/api/identity/platform/usage").status());
    }

    @Test
    void theDeliveryLogCoversEveryCompanyAndNamesThem() throws InterruptedException {
        String platform = Flow.token("PLATFORM_ADMIN");
        // A demo request makes the platform send its own administrators a message, which then shows in the log.
        String marker = UUID.randomUUID().toString().substring(0, 8);
        java.time.OffsetDateTime startedAt = java.time.OffsetDateTime.now().minusSeconds(2);
        Reply sent = identity.post(null, "/api/identity/public/contact", Map.of("name", "Log " + marker, "email", "log." + marker + "@example.com",
                "message", "Please call me about a demo of the whole product.", "topic", "DEMO"));
        assertEquals(200, sent.status());
        JsonNode mine = null;
        for (int i = 0; i < 30 && mine == null; i++) {
            Thread.sleep(1000);
            for (JsonNode d : workflow.get(platform, "/api/workflow/platform/deliveries?page=1&size=50&sort=created_at&dir=desc&orgId=" + identity.get(platform, "/api/identity/me").body().path("organization").path("id").asText() + "").body().path("rows"))
                if (java.time.OffsetDateTime.parse(d.path("created_at").asText()).isAfter(startedAt)) mine = d;
        }
        assertTrue(mine != null, "the message about the enquiry appears in the delivery log");
        assertEquals("PropertyOS Platform", mine.path("org_name").asText(), "the log names the company each message was sent for");
        assertTrue(Set.of("EMAIL", "SMS").contains(mine.path("channel").asText()));
        assertTrue(mine.path("recipient").asText().contains("*"), "recipients are masked: " + mine.path("recipient").asText());
        assertTrue(!mine.has("subject") && !mine.has("body"), "what a message said is never shown to the operator");

        // For support work the whole recipient and the subject can be opened with a reason, which the company can read in its audit log.
        String deliveryId = mine.path("id").asText();
        String reveal = "/api/workflow/platform/deliveries/" + deliveryId + "/reveal";
        assertEquals(400, workflow.post(platform, reveal, Map.of()).status(), "a reason is required");
        assertEquals(400, workflow.post(platform, reveal, Map.of("reason", "short")).status());
        assertEquals(400, workflow.post(platform, reveal, Map.of("reason", "x".repeat(301))).status());
        assertEquals(404, workflow.post(platform, "/api/workflow/platform/deliveries/" + UUID.randomUUID() + "/reveal", Map.of("reason", "Support request 77")).status());
        Reply shown = Flow.ok(workflow.post(platform, reveal, Map.of("reason", "Support request 4411: enquiry alert did not arrive")), "reveal");
        assertTrue(!shown.body().path("recipient").asText().contains("*"), "the whole recipient");
        assertTrue(!shown.body().has("body"), "never the message text");
        assertEquals("PropertyOS Platform", shown.body().path("org_name").asText());
        boolean recorded = false;
        for (JsonNode a : Flow.rows(identity.get(platform, "/api/identity/audit?page=1&size=100")))
            if ("PLATFORM_DELIVERY_VIEWED".equals(a.path("action").asText())) recorded = true;
        assertTrue(recorded, "the viewing is in the audit log");
        assertEquals(403, workflow.post(Flow.token("ORG_ADMIN"), reveal, Map.of("reason", "Please show me this one")).status());
        assertEquals(403, workflow.post(Flow.token("TENANT"), reveal, Map.of("reason", "Please show me this one")).status());

        Reply page = Flow.ok(workflow.get(platform, "/api/workflow/platform/deliveries?page=1&size=2"), "page");
        assertEquals(2, page.body().path("rows").size());
        assertTrue(page.body().path("total").asInt() > 2);
        Set<String> companies = new java.util.HashSet<>();
        for (JsonNode d : Flow.rows(workflow.get(platform, "/api/workflow/platform/deliveries?page=1&size=200"))) {
            companies.add(d.path("org_name").asText());
            assertTrue(!d.path("org_name").asText().isBlank());
        }
        assertTrue(companies.size() >= 2, "messages for several companies are in one log: " + companies);
        for (String status : List.of("SENT", "QUEUED", "SKIPPED"))
            for (JsonNode d : Flow.rows(workflow.get(platform, "/api/workflow/platform/deliveries?page=1&size=50&f.status=" + status))) assertEquals(status, d.path("status").asText());

        JsonNode summary = Flow.ok(workflow.get(platform, "/api/workflow/platform/deliveries/summary"), "summary").body();
        assertTrue(summary.path("last7d").asLong() > 0);
        assertTrue(summary.path("byChannel").isArray() && summary.path("byStatus").isArray());
        assertTrue(summary.path("companies").asLong() >= 1);
        assertTrue(summary.has("held"));

        for (String role : List.of("ORG_ADMIN", "TENANT", "ACCOUNTANT")) {
            assertEquals(403, workflow.get(Flow.token(role), "/api/workflow/platform/deliveries").status(), role);
            assertEquals(403, workflow.get(Flow.token(role), "/api/workflow/platform/deliveries/summary").status(), role);
        }
        assertEquals(401, workflow.get(null, "/api/workflow/platform/deliveries").status());
    }
}
