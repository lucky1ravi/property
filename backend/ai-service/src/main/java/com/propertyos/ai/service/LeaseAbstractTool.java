package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.propertyos.ai.service.StudioText.*;

/** Plain-language summary of a lease: dates, money, obligations and risks. Every figure the model gives is checked against the lease before it is shown. */
@Service
public class LeaseAbstractTool {
    private final Upstream up;
    private final ModelRunner runner;
    private final ObjectMapper mapper;

    public LeaseAbstractTool(Upstream up, ModelRunner runner, ObjectMapper mapper) {
        this.up = up;
        this.runner = runner;
        this.mapper = mapper;
    }

    // Lease abstract
    /**
     * Summarises a lease's key terms, lists risks by severity and notes standard clauses that are missing.
     *
     * @param bearer  the caller's token
     * @param leaseId the lease
     */
    public Map<String, Object> summarise(String bearer, String leaseId) {
        runner.enabled("LEASE_ABSTRACT");
        JsonNode l = up.get(bearer, "/api/leasing/leases/" + leaseId);
        String text = l.path("document_text").asText("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lease", Map.of("id", leaseId, "tenant", l.path("tenant_name").asText(""), "unit", l.path("unit_label").asText(""), "property", l.path("property_name").asText("")));
        JsonNode ai = text.isBlank() ? null : runner.ask("lease-abstract",
                "You extract facts from a US residential lease for a property manager. Return {\"summary\": 2 sentences, \"keyDates\": [{\"label\": string, \"date\": \"YYYY-MM-DD\"}], "
                        + "\"money\": [{\"label\": string, \"amount\": number}], \"obligations\": [{\"party\": \"Tenant\" or \"Landlord\", \"text\": short string}], "
                        + "\"risks\": [{\"severity\": \"HIGH\" or \"MEDIUM\" or \"LOW\", \"text\": string}], \"missing\": [standard lease items that this text does not cover]}. Maximum 6 items per list.",
                "Lease text:\n" + trim(text, 9000) + "\n\nStructured terms: " + l.path("terms"));
        List<String> dropped = new ArrayList<>();
        if (ai != null && ai.path("summary").isTextual()) {
            ObjectNode res = ai.deepCopy();
            // Check every amount and date the model gave against the lease before showing it.
            String norm = text.replaceAll("[,$]", "") + " " + l.path("terms") + " " + l.path("rent") + " " + l.path("deposit");
            ArrayNode money = mapper.createArrayNode();
            for (JsonNode m : arr(ai.path("money"))) {
                String amt = new BigDecimal(m.path("amount").asText("0")).stripTrailingZeros().toPlainString();
                if (norm.contains(amt) || norm.contains(String.format("%.2f", m.path("amount").asDouble()))) money.add(m);
                else dropped.add(m.path("label").asText() + " " + amt);
            }
            res.set("money", money);
            ArrayNode dates = mapper.createArrayNode();
            for (JsonNode m : arr(ai.path("keyDates"))) {
                String dt = m.path("date").asText("");
                if (dt.matches("\\d{4}-\\d{2}-\\d{2}") && (l.toString().contains(dt) || dateInText(text, dt))) dates.add(m);
                else dropped.add(m.path("label").asText() + " " + dt);
            }
            res.set("keyDates", dates);
            out.putAll(mapper.convertValue(res, Map.class));
            out.put("source", "AI");
            out.put("model", runner.modelName());
            out.put("checks", dropped.isEmpty() ? "Every amount and date was matched to the lease text." : "Removed " + dropped.size() + " item(s) that could not be matched to the lease: " + String.join("; ", dropped));
            return out;
        }
        // Rules fallback from the structured lease record.
        JsonNode terms = l.path("terms");
        out.put("summary", l.path("tenant_name").asText("The tenant") + " rents " + l.path("unit_label").asText("the home") + " at " + usd(d(l, "rent")) + " a month from " + l.path("start_date").asText() + " to " + l.path("end_date").asText() + ".");
        out.put("keyDates", List.of(Map.of("label", "Lease starts", "date", l.path("start_date").asText()), Map.of("label", "Lease ends", "date", l.path("end_date").asText())));
        out.put("money", List.of(Map.of("label", "Monthly rent", "amount", d(l, "rent")), Map.of("label", "Security deposit", "amount", d(l, "deposit")), Map.of("label", "Late fee", "amount", d(terms, "lateFee"))));
        out.put("obligations", List.of(Map.of("party", "Tenant", "text", "Pay rent by the 1st; late after " + terms.path("lateFeeGraceDays").asInt(5) + " days."),
                Map.of("party", "Landlord", "text", "Return the deposit within " + terms.path("depositRefundDays").asInt(30) + " days of move-out.")));
        List<Map<String, String>> risks = new ArrayList<>();
        int left = l.path("days_left").asInt(999);
        if (left <= 60 && !"ENDED".equals(l.path("status").asText())) risks.add(Map.of("severity", left <= 30 ? "HIGH" : "MEDIUM", "text", "The lease ends in " + left + " days. Send a renewal offer or plan the move-out."));
        out.put("risks", risks);
        out.put("missing", List.of());
        out.put("source", "RULES");
        out.put("model", "rules");
        out.put("checks", "No AI model answered, so this is built from the lease record.");
        return out;
    }

    private static boolean dateInText(String text, String iso) {
        try {
            java.time.LocalDate dt = java.time.LocalDate.parse(iso);
            String long1 = dt.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " " + dt.getDayOfMonth() + ", " + dt.getYear();
            return text.contains(long1);
        } catch (java.time.format.DateTimeParseException e) {
            return false;
        }
    }
}
