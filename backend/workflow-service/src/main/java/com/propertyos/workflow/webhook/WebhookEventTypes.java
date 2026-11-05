package com.propertyos.workflow.webhook;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
     * What a person reads about an event, sent as {@code text}: a headline, then one bullet for each group of facts the event carries. Slack's incoming
     * webhooks need that field, and it reads well in any tool that shows requests. A test event starts with "[Test]".
     */
    public static String summary(String key, Map<String, ?> data, boolean test) {
        String text = headline(key, data) + details(key, data);
        return test ? "[Test] " + text : text;
    }

    private static String headline(String key, Map<String, ?> data) {
        String unit = str(data, "unitLabel", "the home");
        return switch (key) {
            case "application.submitted" -> "New application: " + str(data, "applicantName", "Someone") + " applied" + (data.get("unit") == null ? "" : " for unit " + data.get("unit"));
            case "lease.sent" -> "Lease sent to " + str(data, "tenantName", "the tenant") + " for " + unit;
            case "lease.signed" -> "MANAGER".equals(data.get("signedBy")) ? "Lease for " + unit + " is fully signed and active"
                    : str(data, "tenantName", "The tenant") + " signed the lease for " + unit;
            case "payment.received" -> "Payment received: $" + str(data, "amount", "0.00") + (data.get("reference") == null ? "" : " (" + data.get("reference") + ")");
            case "work_order.created" -> "New work order: " + str(data, "title", "Repair request")
                    + (data.get("propertyName") == null ? "" : " at " + data.get("propertyName") + (data.get("unitLabel") == null ? "" : ", " + data.get("unitLabel")));
            default -> key;
        };
    }

    // ---------------------------------------------------------------------------------------------- the bullets under the headline

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);

    private static boolean has(Map<String, ?> d, String key) {
        Object v = d.get(key);
        return v != null && !String.valueOf(v).isBlank();
    }

    private static String text(Map<String, ?> d, String key) {
        return has(d, key) ? String.valueOf(d.get(key)) : null;
    }

    private static String money(Object v) {
        try {
            return "$" + String.format(Locale.US, "%,.2f", new BigDecimal(String.valueOf(v)));
        } catch (NumberFormatException e) {
            return "$" + v;
        }
    }

    private static String monthly(Map<String, ?> d, String key, String prefix) {
        return has(d, key) ? prefix + money(d.get(key)) + " a month" : null;
    }

    private static String day(Object v) {
        try {
            return LocalDate.parse(String.valueOf(v)).format(DAY);
        } catch (DateTimeParseException e) {
            return String.valueOf(v);
        }
    }

    private static String clip(String s, int max) {
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= max ? t : t.substring(0, max - 3) + "...";
    }

    private static String channel(String source) {
        return switch (source) {
            case "WEBSITE" -> "Public website";
            case "FACEBOOK_ADS" -> "Facebook ads";
            case "GOOGLE_ADS" -> "Google ads";
            case "ZILLOW" -> "Zillow";
            case "APARTMENTS" -> "Apartments.com";
            case "EMAIL" -> "Email campaign";
            default -> source;
        };
    }

    /** Adds one bullet from the parts that are present, joined with a middle dot; adds nothing when none is. */
    private static void bullet(StringBuilder out, String label, String... parts) {
        List<String> kept = new ArrayList<>();
        for (String p : parts) if (p != null && !p.isBlank()) kept.add(p);
        if (!kept.isEmpty()) out.append("\n• ").append(label).append(": ").append(String.join(" · ", kept));
    }

    /** The facts under the headline, only those the event carries, so a thin event still reads cleanly and never prints "null". */
    private static String details(String key, Map<String, ?> d) {
        StringBuilder out = new StringBuilder();
        switch (key) {
            case "application.submitted" -> {
                if (has(d, "applicantEmail") || has(d, "applicantPhone")) bullet(out, "Applicant", text(d, "applicantName"), text(d, "applicantEmail"), text(d, "applicantPhone"));
                bullet(out, "Home", has(d, "unitLabel") ? text(d, "unitLabel") : text(d, "propertyName"), monthly(d, "rent", "Rent "));
                bullet(out, "Income", monthly(d, "monthlyIncome", ""), has(d, "employer") ? "Employer " + d.get("employer") : null);
                bullet(out, "Move-in", has(d, "moveInDate") ? day(d.get("moveInDate")) : null,
                        has(d, "occupants") ? d.get("occupants") + (" 1".equals(" " + d.get("occupants")) ? " occupant" : " occupants") : null, has(d, "pets") ? "Pets: " + d.get("pets") : null);
                bullet(out, "Came from", has(d, "source") ? channel(String.valueOf(d.get("source"))) : null);
            }
            case "lease.sent", "lease.signed" -> {
                bullet(out, "Property", text(d, "propertyName"));
                bullet(out, "Money", monthly(d, "rent", "Rent "), has(d, "deposit") ? "Deposit " + money(d.get("deposit")) : null);
                bullet(out, "Term", has(d, "startDate") && has(d, "endDate") ? day(d.get("startDate")) + " to " + day(d.get("endDate")) : null);
                if ("lease.signed".equals(key))
                    bullet(out, "Status", "MANAGER".equals(d.get("signedBy")) ? "Fully signed and active" : "Signed by the tenant, waiting for the company to countersign");
                else bullet(out, "Status", "Waiting for the tenant to sign");
            }
            case "payment.received" -> {
                bullet(out, "From", text(d, "tenantName"));
                bullet(out, "Paid with", has(d, "method") ? ("CARD".equals(d.get("method")) ? "Card" : String.valueOf(d.get("method")).replace('_', ' ').toLowerCase(Locale.US)) + (has(d, "last4") ? " ending " + d.get("last4") : "") : null);
                bullet(out, "Applied to", text(d, "appliedTo"));
                bullet(out, "Processing fee", has(d, "fee") && new BigDecimal(String.valueOf(d.get("fee"))).signum() > 0 ? money(d.get("fee")) : null);
            }
            case "work_order.created" -> {
                bullet(out, "Reported by", has(d, "tenantName") ? d.get("tenantName") + ("TENANT".equals(d.get("source")) ? " (resident)" : "")
                        : "TENANT".equals(d.get("source")) ? "A resident" : has(d, "source") ? "Staff" : null);
                bullet(out, "Details", has(d, "description") ? clip(String.valueOf(d.get("description")), 200) : null);
                bullet(out, "Permission to enter", d.get("permissionToEnter") == null ? null : Boolean.TRUE.equals(d.get("permissionToEnter")) ? "Yes" : "No");
            }
            default -> { }
        }
        return out.toString();
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
                d.put("applicantPhone", "512-555-0142");
                d.put("unit", "4B");
                d.put("propertyName", "The Domain Lofts");
                d.put("unitLabel", "The Domain Lofts 4B");
                d.put("rent", "1850.00");
                d.put("monthlyIncome", "6500.00");
                d.put("employer", "Acme Robotics");
                d.put("moveInDate", "2026-12-01");
                d.put("occupants", 2);
                d.put("pets", "One cat");
                d.put("source", "FACEBOOK_ADS");
            }
            case "lease.sent" -> {
                d.put("tenantName", "Maya Patel");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("propertyName", "The Domain Lofts");
                d.put("rent", "1850.00");
                d.put("deposit", "1850.00");
                d.put("startDate", "2026-12-01");
                d.put("endDate", "2027-11-30");
                d.put("status", "SENT");
            }
            case "lease.signed" -> {
                d.put("tenantName", "Maya Patel");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("propertyName", "The Domain Lofts");
                d.put("rent", "1850.00");
                d.put("deposit", "1850.00");
                d.put("startDate", "2026-12-01");
                d.put("endDate", "2027-11-30");
                d.put("status", "TENANT_SIGNED");
                d.put("signedBy", "TENANT");
                d.put("fullySigned", false);
            }
            case "payment.received" -> {
                d.put("tenantId", UUID.randomUUID().toString());
                d.put("tenantName", "Maya Patel");
                d.put("amount", "1850.00");
                d.put("reference", "PAY-DEMO0001");
                d.put("method", "CARD");
                d.put("last4", "4242");
                d.put("appliedTo", "RENT");
            }
            case "work_order.created" -> {
                d.put("title", "Kitchen faucet is leaking");
                d.put("description", "Water pools under the sink after using the tap.");
                d.put("propertyName", "The Domain Lofts");
                d.put("unitLabel", "Domain Lofts 4B");
                d.put("tenantName", "Maya Patel");
                d.put("permissionToEnter", true);
                d.put("source", "TENANT");
            }
            default -> d.put("message", "Sample event");
        }
        return d;
    }
}
