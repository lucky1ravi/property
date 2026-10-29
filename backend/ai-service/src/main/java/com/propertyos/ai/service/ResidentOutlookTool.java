package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.propertyos.ai.service.StudioText.*;

/** Ranks residents by late-payment and move-out risk using simple, explainable rules, then lets the model suggest a kind next step for each. */
@Service
public class ResidentOutlookTool {
    private final Upstream up;
    private final ModelRunner runner;
    private final ObjectMapper mapper;
    private final Guardrails guardrails;

    public ResidentOutlookTool(Upstream up, ModelRunner runner, ObjectMapper mapper, Guardrails guardrails) {
        this.up = up;
        this.runner = runner;
        this.mapper = mapper;
        this.guardrails = guardrails;
    }

    // Resident outlook
    /**
     * Estimates each active resident's late-payment risk and move-out risk from payments, requests, rent against market and the days left on the lease.
     *
     * @param bearer the caller's token
     */
    public Map<String, Object> outlook(String bearer) {
        runner.enabled("RESIDENT_OUTLOOK");
        JsonNode leases = arr(up.tryGet(bearer, "/api/leasing/leases?status=ACTIVE")), units = arr(up.tryGet(bearer, "/api/leasing/units"));
        JsonNode invoices = arr(up.tryGet(bearer, "/api/billing/invoices")), orders = arr(up.tryGet(bearer, "/api/maintenance/work-orders"));
        Map<String, Double> market = new HashMap<>();
        units.forEach(u -> market.put(u.path("id").asText(), u.path("market_rent").asDouble(0)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (JsonNode l : leases) {
            String tenant = l.path("tenant_id").asText();
            int lateCount = 0, maxOverdue = 0;
            double owed = 0;
            for (JsonNode i : invoices) {
                if (!tenant.equals(i.path("tenant_id").asText()) || "VOID".equals(i.path("status").asText())) continue;
                int od = i.path("days_overdue").asInt(0);
                if (od > 0 && d(i, "balance") > 0) { lateCount++; maxOverdue = Math.max(maxOverdue, od); owed += d(i, "balance"); }
            }
            long openRepairs = 0;
            for (JsonNode w : orders) if (tenant.equals(w.path("tenant_id").asText()) && !List.of("COMPLETED", "VERIFIED", "CLOSED", "CANCELLED").contains(w.path("status").asText())) openRepairs++;
            int left = l.path("days_left").asInt(999);
            double rent = d(l, "rent"), mkt = market.getOrDefault(l.path("unit_id").asText(), 0.0);
            int late = Math.min(100, lateCount * 20 + (maxOverdue > 30 ? 40 : maxOverdue > 10 ? 25 : maxOverdue > 0 ? 10 : 0));
            List<String> reasons = new ArrayList<>();
            if (lateCount > 0) reasons.add(lateCount + " overdue invoice" + (lateCount > 1 ? "s" : "") + " (" + usd(owed) + ", oldest " + maxOverdue + " days)");
            int churn = 15;
            if (left <= 30) { churn += 30; reasons.add("lease ends in " + left + " days"); } else if (left <= 60) { churn += 20; reasons.add("lease ends in " + left + " days"); } else if (left <= 90) { churn += 10; reasons.add("lease ends in " + left + " days"); }
            if (openRepairs > 0) { churn += Math.min(30, openRepairs * 15); reasons.add(openRepairs + " repair request" + (openRepairs > 1 ? "s" : "") + " still open"); }
            if (mkt > 0 && mkt > rent * 1.08) { churn += 10; reasons.add("rent is " + Math.round((mkt / rent - 1) * 100) + "% under market, so a renewal increase is likely"); }
            if (late >= 40) { churn += 10; }
            churn = Math.min(100, churn);
            if (reasons.isEmpty()) continue;
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("leaseId", l.path("id").asText());
            r.put("tenant", l.path("tenant_name").asText());
            r.put("unit", l.path("property_name").asText() + " – " + l.path("unit_label").asText());
            r.put("daysLeft", left);
            r.put("latePaymentRisk", late);
            r.put("moveOutRisk", churn);
            r.put("level", Math.max(late, churn) >= 60 ? "HIGH" : Math.max(late, churn) >= 35 ? "MEDIUM" : "LOW");
            r.put("reasons", reasons);
            r.put("action", late >= 40 ? "Call about the balance and offer a payment plan before any notice." : openRepairs > 0 ? "Close out the open repairs, then send a renewal offer." : left <= 60 ? "Send a renewal offer now." : "Check in and ask how the home is working for them.");
            rows.add(r);
        }
        rows.sort((a, b) -> Integer.compare(Math.max((int) b.get("latePaymentRisk"), (int) b.get("moveOutRisk")), Math.max((int) a.get("latePaymentRisk"), (int) a.get("moveOutRisk"))));
        List<Map<String, Object>> top = rows.size() > 12 ? rows.subList(0, 12) : rows;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("residents", top);
        out.put("source", "RULES");
        if (!top.isEmpty()) {
            ArrayNode in = mapper.createArrayNode();
            top.forEach(r -> in.addObject().put("leaseId", String.valueOf(r.get("leaseId"))).put("level", String.valueOf(r.get("level"))).put("reasons", String.valueOf(r.get("reasons"))).put("daysLeft", (int) r.get("daysLeft")));
            JsonNode ai = runner.ask("resident-outlook", "You help a property manager plan outreach. For each resident return one short, kind, practical next step. Return {\"plans\": {\"<leaseId>\": \"one sentence\"}}. Never suggest anything about the person's background, family or protected characteristics, and never suggest threats or eviction.", in.toString());
            if (ai != null && ai.path("plans").isObject()) {
                int used = 0;
                for (Map<String, Object> r : top) {
                    String p = ai.path("plans").path(String.valueOf(r.get("leaseId"))).asText("");
                    if (!p.isBlank() && guardrails.fairHousing(p).isEmpty()) { r.put("action", p); used++; }
                }
                if (used > 0) { out.put("source", "AI+RULES"); out.put("model", runner.modelName()); }
            }
        }
        out.put("note", "Scores are simple, explainable rules on payments, repairs, lease dates and market rent. They help you decide who to contact first. They are not a reason to refuse or end a lease.");
        return out;
    }
}
