package com.propertyos.workflow.webhook;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The events a company can subscribe an outside system to. The key is what the receiver sees ({@code lease.signed}); the domain event is what the rest of
 * PropertyOS publishes. Each type also knows what a realistic sample looks like, for the "send test event" button and the documentation.
 */
public final class WebhookEventTypes {
    private WebhookEventTypes() {
    }

    public record Type(String key, String domainEvent, String label, String description) {
    }

    public static final List<Type> ALL = List.of(
            new Type("application.submitted", "ApplicationSubmitted", "Application submitted", "Someone applied for a home."),
            new Type("lease.sent", "LeaseSent", "Lease sent", "A lease was sent to the tenant to sign."),
            new Type("lease.signed", "LeaseSigned", "Lease signed", "The tenant signed, or the company countersigned and the lease became active. Check signedBy and fullySigned."),
            new Type("payment.received", "PaymentReceived", "Payment received", "A resident's payment succeeded."),
            new Type("work_order.created", "WorkOrderCreated", "Work order created", "A repair request was opened."));

    /** The domain events the webhook handler listens for. */
    public static java.util.Set<String> domainEvents() {
        java.util.Set<String> s = new java.util.LinkedHashSet<>();
        for (Type t : ALL) s.add(t.domainEvent());
        return s;
    }

    public static Optional<Type> byKey(String key) {
        return ALL.stream().filter(t -> t.key().equals(key)).findFirst();
    }

    public static Optional<Type> byDomainEvent(String name) {
        return ALL.stream().filter(t -> t.domainEvent().equals(name)).findFirst();
    }

    private static String str(Map<String, ?> d, String key, String fallback) {
        Object v = d.get(key);
        return v == null || String.valueOf(v).isBlank() ? fallback : String.valueOf(v);
    }

    /**
     * One plain-English line about an event, sent as {@code text}. Slack's incoming webhooks need that field, and it reads well in any tool that shows requests.
     * A test event starts with "[Test]".
     */
    public static String summary(String key, Map<String, ?> data, boolean test) {
        String unit = str(data, "unitLabel", "the home");
        String line = switch (key) {
            case "application.submitted" -> "New application: " + str(data, "applicantName", "Someone") + " applied" + (data.get("unit") == null ? "" : " for unit " + data.get("unit"));
            case "lease.sent" -> "Lease sent to " + str(data, "tenantName", "the tenant") + " for " + unit;
            case "lease.signed" -> "MANAGER".equals(data.get("signedBy")) ? "Lease for " + unit + " is fully signed and active"
                    : str(data, "tenantName", "The tenant") + " signed the lease for " + unit;
            case "payment.received" -> "Payment received: $" + str(data, "amount", "0.00") + (data.get("reference") == null ? "" : " (" + data.get("reference") + ")");
            case "work_order.created" -> "New work order: " + str(data, "title", "Repair request") + (data.get("propertyName") == null ? "" : " at " + data.get("propertyName") + (data.get("unitLabel") == null ? "" : ", " + data.get("unitLabel")));
            default -> key;
        };
        return test ? "[Test] " + line : line;
    }

    /** A made-up but realistic payload for a type, with a fresh id. */
    public static Map<String, Object> sample(String key) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("id", UUID.randomUUID().toString());
        switch (key) {
            case "application.submitted" -> {
                d.put("listingId", UUID.randomUUID().toString());
                d.put("applicantName", "Maya Patel");
                d.put("applicantEmail", "maya.patel@example.com");
                d.put("unit", "4B");
            }
            case "lease.sent" -> {
                d.put("tenantName", "Maya Patel");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("propertyName", "The Domain Lofts");
                d.put("rent", "1850.00");
                d.put("startDate", "2026-12-01");
                d.put("endDate", "2027-11-30");
                d.put("status", "SENT");
            }
            case "lease.signed" -> {
                d.put("tenantName", "Maya Patel");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("propertyName", "The Domain Lofts");
                d.put("rent", "1850.00");
                d.put("status", "TENANT_SIGNED");
                d.put("signedBy", "TENANT");
                d.put("fullySigned", false);
            }
            case "payment.received" -> {
                d.put("tenantId", UUID.randomUUID().toString());
                d.put("amount", "1850.00");
                d.put("reference", "PAY-DEMO0001");
            }
            case "work_order.created" -> {
                d.put("title", "Kitchen faucet is leaking");
                d.put("description", "Water pools under the sink after using the tap.");
                d.put("propertyName", "The Domain Lofts");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("source", "TENANT");
            }
            default -> d.put("message", "Sample event");
        }
        return d;
    }
}
