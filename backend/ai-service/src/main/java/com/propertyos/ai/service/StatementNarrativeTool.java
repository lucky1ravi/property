package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.propertyos.ai.service.StudioText.*;

/** Drafts the short explanation at the top of an owner's monthly statement. A draft containing any dollar amount that is not on the statement is thrown away for a standard template. */
@Service
public class StatementNarrativeTool {
    private static final Logger log = LoggerFactory.getLogger(StatementNarrativeTool.class);
    private final Upstream up;
    private final ModelRunner runner;

    public StatementNarrativeTool(Upstream up, ModelRunner runner) {
        this.up = up;
        this.runner = runner;
    }

    // Owner statement narrative
    private static final Pattern MONEY = Pattern.compile("\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)");

    /**
     * Drafts a plain-language narrative for an owner statement, using only the figures on that statement.
     *
     * @param bearer the caller's token
     * @param id     the statement
     */
    public Map<String, Object> draft(String bearer, String id) {
        runner.enabled("STATEMENT_NARRATIVE");
        JsonNode s = up.get(bearer, "/api/billing/statements/" + id);
        Set<String> allowed = new HashSet<>();
        for (String f : List.of("rent_collected", "fees_collected", "mgmt_fee", "expenses", "net_payout")) allowed.add(norm(d(s, f)));
        for (JsonNode ln : arr(s.path("lines"))) allowed.add(norm(d(ln, "amount")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", Map.of("id", id, "owner", s.path("owner_name").asText(""), "period", s.path("period").asText(""), "status", s.path("status").asText("")));
        JsonNode ai = runner.ask("statement-narrative", "You write the short explanation that goes at the top of a property owner's monthly statement. Return {\"headline\": under 12 words, \"narrative\": 70-110 words in a warm professional tone, \"watch\": array of up to 3 short items the owner may ask about}. "
                + "Quote dollar amounts exactly as given. Do not predict the future or promise anything.", s.toString());
        if (ai != null && ai.path("narrative").isTextual()) {
            String all = ai.path("headline").asText() + " " + ai.path("narrative").asText() + " " + ai.path("watch").toString();
            Matcher m = MONEY.matcher(all);
            List<String> bad = new ArrayList<>();
            while (m.find()) if (!allowed.contains(norm(Double.parseDouble(m.group(1).replace(",", ""))))) bad.add(m.group());
            if (bad.isEmpty()) {
                out.put("headline", ai.path("headline").asText());
                out.put("narrative", ai.path("narrative").asText());
                out.put("watch", ai.path("watch"));
                out.put("source", "AI");
                out.put("model", runner.modelName());
                out.put("checks", "Every dollar amount in the text matches the statement.");
                return out;
            }
            log.warn("Statement narrative had amounts not in the statement: {}", bad);
        }
        double net = d(s, "net_payout"), exp = d(s, "expenses");
        out.put("headline", "Net payout of " + usd(net) + " for " + s.path("period").asText());
        out.put("narrative", "In " + s.path("period").asText() + " we collected " + usd(d(s, "rent_collected")) + " in rent and " + usd(d(s, "fees_collected")) + " in fees. After the management fee of "
                + usd(d(s, "mgmt_fee")) + " and expenses of " + usd(exp) + ", your net payout is " + usd(net) + ".");
        out.put("watch", exp > 0 ? List.of("Expenses of " + usd(exp) + " this period. Line details are listed below.") : List.of());
        out.put("source", "RULES");
        out.put("model", "rules");
        out.put("checks", ai == null ? "No AI model answered, so this uses a standard template." : "The AI draft contained an amount that isn't on the statement, so a standard template is shown.");
        return out;
    }

    private static String norm(double v) {
        return new BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
    }
}
