package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.propertyos.ai.service.StudioText.*;

/** Looks for billing mistakes (duplicate invoices, rent that does not match the lease, repeated expenses). The checks are rules; the model only explains and orders the findings. */
@Service
public class AnomalyScanTool {
    private final Upstream up;
    private final ModelRunner runner;
    private final ObjectMapper mapper;

    public AnomalyScanTool(Upstream up, ModelRunner runner, ObjectMapper mapper) {
        this.up = up;
        this.runner = runner;
        this.mapper = mapper;
    }

    // Anomaly scan
    /**
     * Applies rules to the invoices, statements and leases the caller can see and returns findings with a reason for each, plus a summary (written by AI when a model is available).
     *
     * @param bearer the caller's token, so data access follows their permissions
     */
    public Map<String, Object> scan(String bearer) {
        runner.enabled("ANOMALY_SCAN");
        JsonNode invoices = arr(up.tryGet(bearer, "/api/billing/invoices")), statements = arr(up.tryGet(bearer, "/api/billing/statements")), leases = arr(up.tryGet(bearer, "/api/leasing/leases"));
        Map<String, JsonNode> leaseById = new HashMap<>();
        leases.forEach(x -> leaseById.put(x.path("id").asText(), x));
        List<Map<String, Object>> items = new ArrayList<>();

        Map<String, List<JsonNode>> byKey = new LinkedHashMap<>();
        for (JsonNode i : invoices) {
            if ("VOID".equals(i.path("status").asText())) continue;
            byKey.computeIfAbsent(i.path("tenant_id").asText() + "|" + i.path("kind").asText() + "|" + i.path("period").asText() + "|" + i.path("amount").asText(), k -> new ArrayList<>()).add(i);
        }
        byKey.values().stream().filter(g -> g.size() > 1).forEach(g -> items.add(finding("DUPLICATE_INVOICE", "HIGH", "Possible duplicate invoice for " + g.get(0).path("tenant_name").asText(),
                g.size() + " invoices for the same resident, charge type, period and amount (" + usd(d(g.get(0), "amount")) + ").",
                g.stream().map(x -> x.path("invoice_number").asText() + " · " + x.path("status").asText()).toList(), "Void the extra invoice if it was created twice.", "/app/payments/invoices")));

        for (JsonNode i : invoices) {
            if (!"RENT".equals(i.path("kind").asText()) || "VOID".equals(i.path("status").asText())) continue;
            JsonNode lease = leaseById.get(i.path("lease_id").asText());
            if (lease == null) continue;
            double rent = d(lease, "rent"), amt = d(i, "subtotal") > 0 ? d(i, "subtotal") : d(i, "amount");
            if (rent > 0 && Math.abs(amt - rent) > 0.01 && "ACTIVE".equals(lease.path("status").asText()))
                items.add(finding("RENT_MISMATCH", "MEDIUM", "Rent invoice doesn't match the lease: " + i.path("tenant_name").asText(),
                        "Invoice " + i.path("invoice_number").asText() + " is " + usd(amt) + " but the lease rent is " + usd(rent) + ".",
                        List.of("Invoice " + usd(amt), "Lease " + usd(rent), "Period " + i.path("period").asText()), "Check for a proration or adjustment. Void and reissue if it is an error.", "/app/payments/invoices"));
        }

        for (JsonNode s : statements) {
            Map<String, List<JsonNode>> sameDesc = new LinkedHashMap<>();
            for (JsonNode ln : arr(s.path("lines")))
                if ("Expense".equalsIgnoreCase(ln.path("type").asText()))
                    sameDesc.computeIfAbsent(ln.path("date").asText() + "|" + ln.path("description").asText().toLowerCase(), k -> new ArrayList<>()).add(ln);
            sameDesc.values().stream().filter(g -> g.size() > 1).forEach(g -> items.add(finding("DUPLICATE_EXPENSE", "MEDIUM",
                    "Same expense charged twice on " + s.path("owner_name").asText() + "'s " + s.path("period").asText() + " statement",
                    "\"" + g.get(0).path("description").asText() + "\" appears " + g.size() + " times on " + g.get(0).path("date").asText() + ".",
                    g.stream().map(x -> usd(d(x, "amount"))).toList(), "Confirm both charges are real before the statement is published.", "/app/payments/statements")));
            double collected = d(s, "rent_collected"), exp = d(s, "expenses");
            if (collected > 0 && exp / collected > 0.30)
                items.add(finding("HIGH_EXPENSES", "MEDIUM", "Expenses are " + Math.round(exp / collected * 100) + "% of rent on " + s.path("owner_name").asText() + "'s " + s.path("period").asText() + " statement",
                        "Expenses " + usd(exp) + " against rent collected " + usd(collected) + ".", List.of("Expenses " + usd(exp), "Rent " + usd(collected)), "Review the largest expense lines with the owner.", "/app/payments/statements"));
        }
        items.sort(Comparator.comparingInt((Map<String, Object> m) -> "HIGH".equals(m.get("severity")) ? 0 : 1));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scanned", Map.of("invoices", invoices.size(), "statements", statements.size(), "leases", leases.size()));
        out.put("findings", items);
        String headline = items.isEmpty() ? "Nothing unusual found in " + invoices.size() + " invoices and " + statements.size() + " statements." : items.size() + " item" + (items.size() == 1 ? "" : "s") + " worth checking before month end.";
        out.put("summary", headline);
        out.put("source", "RULES");
        if (!items.isEmpty()) {
            ArrayNode in = mapper.createArrayNode();
            items.stream().limit(10).forEach(m -> in.addObject().put("type", String.valueOf(m.get("type"))).put("title", String.valueOf(m.get("title"))).put("detail", String.valueOf(m.get("detail"))));
            JsonNode ai = runner.ask("anomaly-summary", "You help an accountant at a property management company. Given flagged billing items, return {\"summary\": 2 sentences saying what to check first and why, \"priority\": array of the item titles in the order to check}. Do not add items.", in.toString());
            if (ai != null && ai.path("summary").isTextual() && !ai.path("summary").asText().isBlank()) {
                out.put("summary", ai.path("summary").asText());
                out.put("source", "AI+RULES");
                out.put("model", runner.modelName());
            }
        }
        out.put("note", "Findings come from checks on your own invoices, leases and statements. The AI only explains and orders them; it does not decide what is wrong.");
        return out;
    }

    private static Map<String, Object> finding(String type, String severity, String title, String detail, List<String> evidence, String suggestion, String path) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("severity", severity);
        m.put("title", title);
        m.put("detail", detail);
        m.put("evidence", evidence);
        m.put("suggestion", suggestion);
        m.put("path", path);
        return m;
    }
}
