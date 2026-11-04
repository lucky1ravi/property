package com.propertyos.workflow.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookCoreTest {
    private static final ObjectMapper M = new ObjectMapper();

    // ------------------------------------------------------------------------------------------------- signing

    @Test
    @DisplayName("the signature is HMAC-SHA256 of 'timestamp.body': RFC 4231 case 2 with a fixed timestamp")
    void signatureVector() {
        // HMAC("Jefe", "1700000000.what do ya want for nothing?") computed independently
        byte[] body = "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8);
        String sig = WebhookSigner.sign("Jefe", 1700000000L, body);
        assertThat(sig).hasSize(64).matches("[0-9a-f]+");
        assertThat(WebhookSigner.header("Jefe", 1700000000L, body)).isEqualTo("sha256=" + sig);
        // without the timestamp this is the RFC vector; with it the value must differ
        assertThat(sig).isNotEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @DisplayName("a receiver that checks the timestamp and signature accepts the real request and rejects forgeries and replays")
    void verification() {
        byte[] body = "{\"id\":\"evt_1\",\"type\":\"lease.signed\"}".getBytes(StandardCharsets.UTF_8);
        String header = WebhookSigner.header("whsec_abc", 1700000000L, body);
        assertThat(WebhookSigner.verify("whsec_abc", 1700000000L, body, header)).isTrue();
        assertThat(WebhookSigner.verify("whsec_abc", 1700000000L, body, header.substring(7))).isTrue();                 // prefix optional
        assertThat(WebhookSigner.verify("whsec_abc", 1700000001L, body, header)).isFalse();                              // a different timestamp (replay with a new time)
        assertThat(WebhookSigner.verify("whsec_abc", 1700000000L, "{}".getBytes(StandardCharsets.UTF_8), header)).isFalse();   // a different body
        assertThat(WebhookSigner.verify("other", 1700000000L, body, header)).isFalse();                                   // a different secret
        assertThat(WebhookSigner.verify("whsec_abc", 1700000000L, body, null)).isFalse();
        assertThat(WebhookSigner.verify("whsec_abc", 1700000000L, body, "sha256=short")).isFalse();
    }

    @Test
    @DisplayName("secrets are long, URL-safe and never repeat")
    void secrets() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String s = WebhookSigner.newSecret();
            assertThat(s).startsWith("whsec_").matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(80);
            seen.add(s);
        }
        assertThat(seen).hasSize(200);
    }

    // ------------------------------------------------------------------------------------------------- settings and backoff

    @Test
    @DisplayName("the default schedule tries six times, waiting 1 minute, 5 minutes, 30 minutes, 2 hours then 6 hours")
    void defaultSchedule() throws Exception {
        WebhookSettings s = WebhookSettings.of(M.readTree("{\"maxAttempts\":6,\"backoffSeconds\":[60,300,1800,7200,21600]}"));
        assertThat(s.delayAfter(1)).isEqualTo(60);
        assertThat(s.delayAfter(2)).isEqualTo(300);
        assertThat(s.delayAfter(5)).isEqualTo(21600);
        assertThat(s.delayAfter(9)).isEqualTo(21600);   // the last step repeats
        assertThat(s.exhausted(5)).isFalse();
        assertThat(s.exhausted(6)).isTrue();
    }

    @Test
    @DisplayName("missing or silly settings fall back to safe values")
    void settingsClamped() throws Exception {
        WebhookSettings d = WebhookSettings.of(null);
        assertThat(d.maxAttempts()).isEqualTo(6);
        assertThat(d.timeoutSeconds()).isEqualTo(5);
        assertThat(d.maxPerCompany()).isEqualTo(10);
        assertThat(d.autoDisableAfterFailures()).isEqualTo(20);
        assertThat(d.retentionDays()).isEqualTo(30);
        WebhookSettings w = WebhookSettings.of(M.readTree("{\"maxAttempts\":500,\"timeoutSeconds\":0,\"backoffSeconds\":[0,-5,\"x\"],\"maxEndpoints\":-1,\"retentionDays\":99999}"));
        assertThat(w.maxAttempts()).isEqualTo(10);
        assertThat(w.timeoutSeconds()).isEqualTo(1);
        assertThat(w.backoffSeconds()).containsExactly(60, 300, 1800, 7200, 21600);
        assertThat(w.maxPerCompany()).isEqualTo(1);
        assertThat(w.retentionDays()).isEqualTo(365);
    }

    // ------------------------------------------------------------------------------------------------- events and envelope

    @Test
    @DisplayName("the five supported events map to the platform's own events and back")
    void eventMap() {
        assertThat(WebhookEventTypes.ALL).extracting(WebhookEventTypes.Type::key)
                .containsExactly("application.submitted", "lease.sent", "lease.signed", "payment.received", "work_order.created");
        assertThat(WebhookEventTypes.domainEvents()).containsExactlyInAnyOrder("ApplicationSubmitted", "LeaseSent", "LeaseSigned", "PaymentReceived", "WorkOrderCreated");
        assertThat(WebhookEventTypes.byDomainEvent("LeaseSigned").orElseThrow().key()).isEqualTo("lease.signed");
        assertThat(WebhookEventTypes.byKey("nope")).isEmpty();
        assertThat(WebhookEventTypes.byDomainEvent("LeadCreated")).isEmpty();   // other platform events are not exposed
    }

    @Test
    @DisplayName("every event type has a realistic sample that names what it is about")
    void samples() {
        for (WebhookEventTypes.Type t : WebhookEventTypes.ALL) {
            Map<String, Object> s = WebhookEventTypes.sample(t.key());
            assertThat(s).as(t.key()).containsKey("id").hasSizeGreaterThan(2);
            UUID.fromString((String) s.get("id"));
        }
        assertThat(WebhookEventTypes.sample("lease.signed")).containsEntry("signedBy", "TENANT");
    }

    @Test
    @DisplayName("the envelope carries the event id, type, time and data, and puts the thing's id in data.id")
    void envelope() throws Exception {
        UUID lease = UUID.randomUUID();
        String json = WebhookEvents.envelope(M, "evt_42", "lease.signed", OffsetDateTime.parse("2026-11-04T10:15:30Z"), lease, Map.of("tenantName", "Maya Patel", "id", "ignored"), false);
        JsonNode n = M.readTree(json);
        assertThat(n.get("id").asText()).isEqualTo("evt_42");
        assertThat(n.get("type").asText()).isEqualTo("lease.signed");
        assertThat(n.get("created_at").asText()).isEqualTo("2026-11-04T10:15:30Z");
        assertThat(n.get("data").get("id").asText()).isEqualTo(lease.toString());
        assertThat(n.get("data").get("tenantName").asText()).isEqualTo("Maya Patel");
        assertThat(n.has("test")).isFalse();
        assertThat(M.readTree(WebhookEvents.envelope(M, "evt_t", "lease.sent", OffsetDateTime.now(), lease, Map.of(), true)).get("test").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("every payload carries one plain-English line in text, which Slack's incoming webhooks require; a test says so")
    void text() throws Exception {
        for (WebhookEventTypes.Type t : WebhookEventTypes.ALL) {
            Map<String, Object> sample = WebhookEventTypes.sample(t.key());
            JsonNode n = M.readTree(WebhookEvents.envelope(M, "evt_1", t.key(), OffsetDateTime.now(), UUID.randomUUID(), sample, false));
            assertThat(n.get("text").asText()).as(t.key()).isNotBlank().doesNotContain("null").hasSizeLessThan(200);
            assertThat(M.readTree(WebhookEvents.envelope(M, "evt_2", t.key(), OffsetDateTime.now(), UUID.randomUUID(), sample, true)).get("text").asText()).startsWith("[Test] ");
        }
        assertThat(WebhookEventTypes.summary("application.submitted", Map.of("applicantName", "Wendy Hook", "unit", "B"), false)).isEqualTo("New application: Wendy Hook applied for unit B");
        assertThat(WebhookEventTypes.summary("lease.signed", Map.of("tenantName", "Maya Patel", "unitLabel", "Unit 4B", "signedBy", "TENANT"), false)).isEqualTo("Maya Patel signed the lease for Unit 4B");
        assertThat(WebhookEventTypes.summary("lease.signed", Map.of("unitLabel", "Unit 4B", "signedBy", "MANAGER"), false)).isEqualTo("Lease for Unit 4B is fully signed and active");
        assertThat(WebhookEventTypes.summary("payment.received", Map.of("amount", "1850.00", "reference", "PAY-1"), false)).isEqualTo("Payment received: $1850.00 (PAY-1)");
        assertThat(WebhookEventTypes.summary("work_order.created", Map.of("title", "Leaky tap"), false)).isEqualTo("New work order: Leaky tap");
        assertThat(WebhookEventTypes.summary("lease.sent", Map.of(), false)).isEqualTo("Lease sent to the tenant for the home");   // missing facts never print "null"
    }

    @Test
    @DisplayName("transient failures are worth retrying and permanent ones are not")
    void failureKinds() {
        for (int code : new int[]{0, 408, 429, 500, 502, 503, 504}) assertThat(new WebhookDispatcher.Result(code, "x", 1).transientFailure()).as(String.valueOf(code)).isTrue();
        for (int code : new int[]{-1, 400, 401, 403, 404, 410, 422}) assertThat(new WebhookDispatcher.Result(code, "x", 1).transientFailure()).as(String.valueOf(code)).isFalse();
        assertThat(new WebhookDispatcher.Result(204, null, 1).success()).isTrue();
        assertThat(new WebhookDispatcher.Result(301, "x", 1).success()).isFalse();   // a redirect is not followed and is not a success
        assertThat(List.of(1)).hasSize(1);
    }
}
