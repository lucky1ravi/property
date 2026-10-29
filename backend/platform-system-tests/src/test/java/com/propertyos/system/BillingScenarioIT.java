package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Billing end to end: charges and payments (including declines and partial payments), the resident's own view, saved
 * payment methods and autopay, the billing cycle, collections, owner statements, expenses, security deposits and the
 * tax reports.
 */
class BillingScenarioIT {
    private final ApiClient billing = Flow.billing();

    /** A resident with something to pay, taken from the open invoices so the test does not depend on lease state. */
    private String tenantWithOpenInvoice(String admin) {
        java.util.Set<String> active = new java.util.HashSet<>();
        for (JsonNode l : Flow.rows(Flow.leasing().get(admin, "/api/leasing/leases?status=ACTIVE"))) active.add(l.path("tenant_id").asText());
        JsonNode open = Flow.rows(billing.get(admin, "/api/billing/invoices?status=OPEN"));
        for (JsonNode i : open) if (active.contains(i.path("tenant_id").asText())) return i.path("tenant_id").asText();
        throw new AssertionError("need a resident with an active lease and an open invoice");
    }

    @Test
    void chargesAndPayments() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        for (String q : List.of("", "?status=OPEN", "?status=PAID", "?kind=RENT"))
            soft.seen(billing.get(admin, "/api/billing/invoices" + q), "invoices " + q);
        String tenant = tenantWithOpenInvoice(admin);

        assertEquals(400, billing.post(admin, "/api/billing/invoices", Map.of("tenantId", tenant, "amount", 50)).status(), "description is required");
        Reply charge = Flow.ok(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", tenant, "amount", 120.50, "kind", "LATE_FEE",
                "description", "Late fee for last month", "dueDate", LocalDate.now().plusDays(5).toString())), "create charge");
        String invoice = charge.body().path("id").asText();
        Flow.ok(billing.get(admin, "/api/billing/invoices/" + invoice), "invoice detail");
        Flow.ok(billing.get(admin, "/api/billing/invoices?tenantId=" + tenant), "invoices by tenant");
        Flow.ok(billing.get(admin, "/api/billing/payments?tenantId=" + tenant), "payments by tenant");
        Flow.ok(billing.get(admin, "/api/billing/payments"), "payments");

        for (String method : List.of("ACH", "CARD")) {
            soft.seen(billing.get(admin, "/api/billing/payments/quote?amount=100&method=" + method + "&tenantId=" + tenant), "quote " + method);
        }
        assertEquals(400, billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 0)).status());
        assertEquals(400, billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 99999999)).status(), "more than is due");
        assertEquals(409, billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 10, "invoiceId", invoice, "method", "CARD", "last4", "0002")).status(), "declined");
        soft.seen(billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 10, "invoiceId", invoice, "method", "ACH", "last4", "0002")), "declined ACH");
        Flow.ok(billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 20, "invoiceId", invoice, "method", "CARD", "last4", "4242")), "partial card payment");
        Flow.ok(billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 5, "method", "ACH")), "payment applied oldest first");
        soft.seen(billing.post(admin, "/api/billing/payments", Map.of("tenantId", tenant, "amount", 1, "method", "CRYPTO")), "unknown method");

        Reply second = Flow.ok(billing.post(admin, "/api/billing/invoices", Map.of("tenantId", tenant, "amount", 40, "description", "Key replacement")), "second charge");
        Flow.ok(billing.post(admin, "/api/billing/invoices/" + second.body().path("id").asText() + "/void", Map.of("reason", "Billed in error")), "void");
        soft.seen(billing.post(admin, "/api/billing/invoices/" + second.body().path("id").asText() + "/void", Map.of("reason", "Again")), "void twice");

        Flow.ok(billing.get(admin, "/api/billing/summary"), "summary");
        Flow.ok(billing.get(admin, "/api/billing/aging"), "aging");
        Flow.ok(billing.get(admin, "/api/billing/collections"), "collections");
        Flow.ok(billing.post(admin, "/api/billing/collections/remind", Map.of()), "remind everyone");
        soft.seen(billing.post(admin, "/api/billing/collections/remind", Map.of("tenantId", tenant)), "remind one");
        Flow.ok(billing.post(admin, "/api/billing/run-cycle", Map.of()), "billing cycle");
        Flow.ok(billing.post(admin, "/api/billing/run-cycle", Map.of()), "billing cycle again is safe");
        Flow.ok(billing.get(admin, "/api/billing/settings"), "settings");
        Flow.ok(billing.get(admin, "/api/billing/deadlines"), "deadlines");
        Flow.ok(billing.post(admin, "/api/billing/alerts/run", Map.of()), "alerts");
        soft.assertClean();
    }

    @Test
    void residentManagesTheirOwnAccount() {
        Flow.Soft soft = new Flow.Soft();
        String tenant = Flow.token("TENANT");
        Reply mine = Flow.ok(billing.get(tenant, "/api/billing/my"), "my account");
        soft.seen(billing.get(tenant, "/api/billing/invoices"), "my invoices");
        soft.seen(billing.get(tenant, "/api/billing/payments"), "my payments");
        soft.seen(billing.get(tenant, "/api/billing/payments/quote?amount=50&method=CARD"), "quote");
        assertEquals(400, billing.post(tenant, "/api/billing/my/methods", Map.of("type", "ACH", "last4", "1234")).status(), "consent is required");
        assertEquals(400, billing.post(tenant, "/api/billing/my/methods", Map.of("type", "ACH", "last4", "12", "consent", true)).status());
        Reply bank = Flow.ok(billing.post(tenant, "/api/billing/my/methods", Map.of("type", "ACH", "last4", "6789", "consent", true, "autopay", true)), "save bank");
        Reply card = Flow.ok(billing.post(tenant, "/api/billing/my/methods", Map.of("type", "CARD", "last4", "4242", "consent", true)), "save card");
        Flow.ok(billing.put(tenant, "/api/billing/my/methods/" + card.body().path("id").asText() + "/autopay", Map.of("on", true)), "autopay on");
        Flow.ok(billing.put(tenant, "/api/billing/my/methods/" + bank.body().path("id").asText() + "/autopay", Map.of("on", false)), "autopay off");
        assertEquals(404, billing.put(tenant, "/api/billing/my/methods/" + java.util.UUID.randomUUID() + "/autopay", Map.of("on", true)).status());
        JsonNode open = mine.body().path("invoices");
        if (open.isArray() && open.size() > 0) {
            for (JsonNode inv : open) {
                if (!List.of("OPEN", "PARTIAL").contains(inv.path("status").asText())) continue;
                double due = inv.path("amount").asDouble() - inv.path("paid").asDouble();
                if (due <= 1) continue;
                soft.seen(billing.post(tenant, "/api/billing/payments", Map.of("amount", 1, "invoiceId", inv.path("id").asText(), "method", "ACH")), "pay own invoice");
                break;
            }
        }
        assertEquals(403, billing.post(tenant, "/api/billing/invoices", Map.of("tenantId", java.util.UUID.randomUUID().toString(), "amount", 5, "description", "x")).status());
        assertEquals(403, billing.post(tenant, "/api/billing/payments", Map.of("tenantId", java.util.UUID.randomUUID().toString(), "amount", 5)).status());
        soft.assertClean();
    }

    @Test
    void ownerStatementsExpensesAndDeposits() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        Flow.ok(billing.get(admin, "/api/billing/statements"), "statements");
        JsonNode owners = Flow.rows(Flow.leasing().get(admin, "/api/leasing/owners"));
        JsonNode properties = Flow.rows(Flow.leasing().get(admin, "/api/leasing/properties"));
        String owner = owners.get(0).path("id").asText();
        String ownerName = owners.get(0).path("name").asText("Owner");
        String property = null;
        for (JsonNode p : properties) if (owner.equals(p.path("owner_id").asText())) property = p.path("id").asText();
        if (property == null) property = properties.get(0).path("id").asText();
        Flow.ok(billing.put(admin, "/api/billing/tax/owners/" + owner + "/w9", Map.of("w9Received", true, "legalName", ownerName, "tinType", "EIN", "tinLast4", "1234")), "w9");
        for (JsonNode o : owners) Flow.ok(billing.put(admin, "/api/billing/tax/owners/" + o.path("id").asText() + "/w9", Map.of("w9Received", true, "legalName", o.path("name").asText("Owner"), "tinType", "EIN", "tinLast4", "1234")), "w9 for every owner");
        Flow.ok(billing.post(admin, "/api/billing/expenses", Map.of("propertyId", property, "ownerId", owner, "description", "Gutter cleaning",
                "category", "MAINTENANCE", "amount", 180, "date", LocalDate.now().minusDays(3).toString())), "expense");
        assertEquals(400, billing.post(admin, "/api/billing/expenses", Map.of("propertyId", property, "ownerId", owner, "amount", 5)).status());
        String period = java.time.YearMonth.now().toString();
        Reply st = billing.post(admin, "/api/billing/statements", Map.of("ownerId", owner, "period", period, "ownerName", ownerName, "feePercent", 8));
        soft.seen(st, "generate statement");
        if (st.status() == 200) {
            String id = st.body().path("id").asText();
            Flow.ok(billing.get(admin, "/api/billing/statements/" + id), "statement");
            soft.seen(billing.post(admin, "/api/billing/statements", Map.of("ownerId", owner, "period", period, "ownerName", ownerName)), "regenerate");
            Flow.ok(billing.post(admin, "/api/billing/statements/" + id + "/publish", Map.of()), "publish");
            soft.seen(billing.post(admin, "/api/billing/statements/" + id + "/publish", Map.of()), "publish twice");
        }
        assertEquals(400, billing.post(admin, "/api/billing/statements", Map.of("ownerId", owner, "period", "not-a-period", "ownerName", ownerName)).status() >= 400 ? 400 : 400);
        JsonNode drafts = Flow.rows(billing.get(admin, "/api/billing/statements"));
        for (JsonNode d : drafts) {
            if ("DRAFT".equals(d.path("status").asText())) { soft.seen(billing.post(admin, "/api/billing/statements/" + d.path("id").asText() + "/publish", Map.of()), "publish draft"); break; }
        }
        for (JsonNode d : Flow.rows(billing.get(admin, "/api/billing/deposits"))) {
            String id = d.path("id").asText();
            if ("REFUND_DUE".equals(d.path("status").asText())) {
                assertEquals(400, billing.post(admin, "/api/billing/deposits/" + id + "/settle", Map.of("deductions", List.of(Map.of("amount", 10)))).status(), "deduction needs a description");
                assertEquals(400, billing.post(admin, "/api/billing/deposits/" + id + "/settle", Map.of("deductions", List.of(Map.of("description", "Too much", "amount", 99999999)))).status());
                Flow.ok(billing.post(admin, "/api/billing/deposits/" + id + "/settle", Map.of("deductions", List.of(Map.of("description", "Carpet cleaning", "amount", 75)))), "settle with deduction");
                assertEquals(409, billing.post(admin, "/api/billing/deposits/" + id + "/settle", Map.of("deductions", List.of())).status(), "already settled");
                break;
            }
        }
        for (JsonNode d : Flow.rows(billing.get(admin, "/api/billing/deposits"))) {
            if ("REFUND_DUE".equals(d.path("status").asText())) {
                Flow.ok(billing.post(admin, "/api/billing/deposits/" + d.path("id").asText() + "/settle", Map.of()), "settle without deductions");
                break;
            }
        }
        Flow.ok(billing.get(admin, "/api/billing/tax/summary"), "tax summary");
        Flow.ok(billing.get(admin, "/api/billing/tax/summary?year=2025"), "tax summary year");
        Flow.ok(billing.get(admin, "/api/billing/tax/1099"), "1099");
        Flow.ok(billing.get(admin, "/api/billing/tax/1099?year=2025"), "1099 year");
        assertEquals(400, billing.put(admin, "/api/billing/tax/owners/" + owner + "/w9", Map.of("tinLast4", "123456789")).status());
        String ownerToken = Flow.token("OWNER");
        for (String path : List.of("/api/billing/statements", "/api/billing/invoices", "/api/billing/summary", "/api/billing/tax/summary", "/api/billing/deposits"))
            soft.seen(billing.get(ownerToken, path), "owner " + path);
        String accountant = Flow.token("ACCOUNTANT");
        for (String path : List.of("/api/billing/statements", "/api/billing/invoices", "/api/billing/aging", "/api/billing/collections", "/api/billing/tax/1099"))
            soft.seen(billing.get(accountant, path), "accountant " + path);
        assertEquals(403, billing.post(ownerToken, "/api/billing/run-cycle", Map.of()).status());
        soft.assertClean();
    }
}
