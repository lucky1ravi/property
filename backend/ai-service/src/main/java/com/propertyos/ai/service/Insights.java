package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Profit opportunities and the broker's prioritised work queue. Both are computed from the data the signed-in user is allowed
 * to see (their own token is used for every read), so a broker only gets opportunities for assigned properties.
 */
@Service
public class Insights {
    private final Upstream up;
    private final ConfigClient config;

    public Insights(Upstream up, ConfigClient config) {
        this.up = up;
        this.config = config;
    }

    private static Map<String, Object> item(String type, String title, String detail, double impact, String impactKind, String effort, String actionLabel, String path, double confidence) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("title", title);
        m.put("detail", detail);
        m.put("impact", Math.round(impact));
        m.put("impactKind", impactKind);           // ANNUAL, ONE_TIME
        m.put("effort", effort);
        m.put("actionLabel", actionLabel);
        m.put("path", path);
        m.put("confidence", confidence);
        return m;
    }

    private static JsonNode arr(JsonNode n) {
        return n == null || !n.isArray() ? com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.arrayNode() : n;
    }

    // ---------- Profit opportunities ----------
    public Map<String, Object> opportunities(String bearer) {
        JsonNode units = arr(up.tryGet(bearer, "/api/leasing/units")), leases = arr(up.tryGet(bearer, "/api/leasing/leases?status=ACTIVE"));
        JsonNode listings = arr(up.tryGet(bearer, "/api/leasing/listings")), orders = arr(up.tryGet(bearer, "/api/maintenance/work-orders"));
        JsonNode collections = arr(up.tryGet(bearer, "/api/billing/collections"));
        double maxPct = config.number("renewals.maxIncreasePercent", "6").doubleValue() / 100.0;
        Map<String, JsonNode> unitById = new HashMap<>();
        units.forEach(u -> unitById.put(u.path("id").asText(), u));
        List<Map<String, Object>> out = new ArrayList<>();

        // 1. Renewals priced toward market, within the organisation's cap.
        for (JsonNode l : leases) {
            JsonNode u = unitById.get(l.path("unit_id").asText());
            if (u == null || l.path("days_left").asInt(999) > 120 || !l.path("renewal").isNull() && !l.path("renewal").isMissingNode()) continue;
            double rent = l.path("rent").asDouble(), market = u.path("market_rent").asDouble();
            double target = Math.min(market, rent * (1 + maxPct));
            if (target - rent < 20) continue;
            out.add(item("RENEWAL_UPLIFT", "Renew " + l.path("property_name").asText() + " " + l.path("unit_label").asText() + " at $" + Math.round(target),
                    l.path("tenant_name").asText() + "'s lease ends in " + l.path("days_left").asInt() + " days. Rent is $" + Math.round(rent) + " against a market rent of $" + Math.round(market)
                            + ". A " + Math.round((target / rent - 1) * 1000) / 10.0 + "% increase stays within your " + Math.round(maxPct * 100) + "% limit.",
                    (target - rent) * 12, "ANNUAL", "LOW", "Offer renewal", "/leasing/leases/" + l.path("id").asText(), 0.8));
        }
        // 2. Vacancy cost.
        for (JsonNode u : units) {
            if (!"VACANT".equals(u.path("status").asText())) continue;
            int days = u.hasNonNull("vacant_since") ? (int) ChronoUnit.DAYS.between(LocalDate.parse(u.path("vacant_since").asText()), LocalDate.now()) : 0;
            double perDay = u.path("market_rent").asDouble() / 30.0;
            boolean listed = false;
            for (JsonNode li : listings) if (li.path("unit_id").asText().equals(u.path("id").asText()) && !"LEASED".equals(li.path("status").asText())) listed = true;
            if (days < 7 && listed) continue;
            out.add(item("VACANCY", (listed ? "Speed up " : "List ") + u.path("property_name").asText() + " " + u.path("number").asText(),
                    "Vacant " + days + " days, costing about $" + Math.round(perDay) + " a day in lost rent" + (listed ? ". It's listed, so check the price and photos." : " and it isn't listed yet."),
                    perDay * 30, "ONE_TIME", listed ? "MEDIUM" : "LOW", listed ? "Review listing" : "Create listing", listed ? "/leasing/listings" : "/leasing/properties", 0.7));
        }
        // 3. Listings getting views but no enquiries: price test.
        for (JsonNode li : listings) {
            if (!"LIVE".equals(li.path("status").asText()) || li.path("views").asInt() < 60 || li.path("enquiries").asInt() > 3) continue;
            double rent = li.path("rent").asDouble();
            out.add(item("PRICE_TEST", "Test a lower rent on " + li.path("property_name").asText() + " " + li.path("unit_number").asText(),
                    li.path("views").asInt() + " views but only " + li.path("enquiries").asInt() + " enquiries suggests the price is high. A 3% reduction ($" + Math.round(rent * 0.03) + ") can fill it sooner.",
                    rent * 0.5, "ONE_TIME", "LOW", "Adjust price", "/leasing/listings/" + li.path("id").asText(), 0.55));
        }
        // 4. Collections.
        double owed = 0;
        int tenants = 0;
        for (JsonNode c : collections) {
            owed += c.path("balance").asDouble();
            tenants++;
        }
        if (tenants > 0)
            out.add(item("COLLECTIONS", "Recover $" + Math.round(owed) + " in overdue rent", tenants + " resident(s) are behind. Start with the highest-risk accounts and offer payment plans before fees compound.",
                    owed, "ONE_TIME", "MEDIUM", "Open collections", "/payments/collections", 0.65));
        // 5. Maintenance hotspots.
        Map<String, List<JsonNode>> byProp = new HashMap<>();
        for (JsonNode w : orders) if (List.of("PLUMBING", "HVAC").contains(w.path("category").asText())) byProp.computeIfAbsent(w.path("property_name").asText(), k -> new ArrayList<>()).add(w);
        for (var e : byProp.entrySet()) {
            if (e.getValue().size() < 3) continue;
            double spend = e.getValue().stream().mapToDouble(w -> w.hasNonNull("final_cost") ? w.path("final_cost").asDouble() : w.path("estimate").asDouble()).sum();
            out.add(item("HOTSPOT", e.getKey() + " has " + e.getValue().size() + " plumbing and HVAC repairs",
                    "Repeat repairs of the same kind cost about $" + Math.round(spend) + ". A scheduled inspection or equipment replacement usually cuts this by around 40%.",
                    spend * 0.4 * 4, "ANNUAL", "MEDIUM", "Schedule inspection", "/maintenance", 0.5));
        }
        out.sort((a, b) -> Long.compare(annual(b), annual(a)));
        long annual = out.stream().mapToLong(Insights::annual).sum();
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", out);
        res.put("totalAnnualImpact", annual);
        res.put("count", out.size());
        res.put("note", "Estimates are based on current rents, vacancy and repair history within your policy limits. They are guidance, not guarantees.");
        return res;
    }

    private static long annual(Map<String, Object> m) {
        return (Long) m.get("impact");
    }

    // ---------- Broker queue ----------
    public Map<String, Object> brokerQueue(String bearer) {
        JsonNode orders = arr(up.tryGet(bearer, "/api/maintenance/work-orders?status=ACTIVE")), apps = arr(up.tryGet(bearer, "/api/leasing/applications"));
        JsonNode collections = arr(up.tryGet(bearer, "/api/billing/collections")), leads = arr(up.tryGet(bearer, "/api/leasing/leads"));
        JsonNode leases = arr(up.tryGet(bearer, "/api/leasing/leases?status=ACTIVE")), showings = arr(up.tryGet(bearer, "/api/leasing/showings"));
        JsonNode listings = arr(up.tryGet(bearer, "/api/leasing/listings?status=FLAGGED")), props = arr(up.tryGet(bearer, "/api/leasing/properties"));
        JsonNode automation = up.tryGet(bearer, "/api/workflow/automation/summary");
        List<Map<String, Object>> q = new ArrayList<>();
        for (JsonNode w : orders) {
            String st = w.path("status").asText(), pr = w.path("priority").asText();
            if ("AWAITING_APPROVAL".equals(st)) q.add(task("APPROVAL", "Approve repair: " + w.path("title").asText(), w.path("property_name").asText() + " · estimate $" + w.path("estimate").asText(), 70, w.path("estimate").asDouble(), "/maintenance/" + w.path("id").asText(), "Review and approve"));
            else if ("EMERGENCY".equals(pr) && !List.of("COMPLETED", "CLOSED").contains(st)) q.add(task("EMERGENCY", "Emergency: " + w.path("title").asText(), w.path("property_name").asText() + " · " + st.toLowerCase().replace('_', ' '), 95, 0, "/maintenance/" + w.path("id").asText(), "Check progress"));
            else if ("COMPLETED".equals(st)) q.add(task("VERIFY", "Confirm fixed: " + w.path("title").asText(), "Completed, waiting for sign-off", 30, w.path("final_cost").asDouble(), "/maintenance/" + w.path("id").asText(), "Verify and close"));
        }
        for (JsonNode a : apps) {
            String st = a.path("status").asText();
            if (List.of("SCREENED", "OWNER_REVIEW").contains(st)) {
                String rec = a.path("recommendation").asText("").replace("\"", "");
                q.add(task("APPLICATION", "Decide: " + a.path("applicant_name").asText(), a.path("unit_label").asText() + " · AI recommends " + (rec.isBlank() ? "review" : rec.toLowerCase()), 75, a.path("rent").asDouble() * 12, "/leasing/applications/" + a.path("id").asText(), rec.equals("APPROVE") ? "Approve" : "Review"));
            }
        }
        for (JsonNode c : collections) {
            int days = c.path("oldest_days").asInt();
            q.add(task("COLLECTIONS", "Collect from " + c.path("tenant_name").asText(), "$" + c.path("balance").asText() + " overdue " + days + " days · " + c.path("risk_level").asText().toLowerCase() + " risk", Math.min(90, 40 + days), c.path("balance").asDouble(), "/payments/collections", c.path("recommendation").asText()));
        }
        for (JsonNode l : leads) {
            if (!"NEW".equals(l.path("status").asText())) continue;
            int sc = l.path("ai_score").asInt();
            q.add(task("LEAD", "Reply to " + l.path("name").asText(), "Score " + sc + " · " + l.path("listing_title").asText(""), 35 + sc / 4, 0, "/leasing/leads", "Send the drafted reply"));
        }
        for (JsonNode l : leases) {
            int d = l.path("days_left").asInt(999);
            if (d <= 75 && (l.path("renewal").isNull() || l.path("renewal").isMissingNode()))
                q.add(task("RENEWAL", "Renewal due: " + l.path("unit_label").asText(), l.path("tenant_name").asText() + " · lease ends in " + d + " days", 60 - d / 3, l.path("rent").asDouble() * 12, "/leasing/leases/" + l.path("id").asText(), "Offer renewal"));
        }
        for (JsonNode s : showings) if ("REQUESTED".equals(s.path("status").asText()))
            q.add(task("SHOWING", "Confirm tour for " + s.path("prospect_name").asText(), s.path("listing_title").asText(), 55, 0, "/leasing/showings", "Confirm"));
        for (JsonNode l : listings) q.add(task("LISTING", "Listing flagged: " + l.path("title").asText(), "Fair-housing review needs a person", 65, 0, "/leasing/listings/" + l.path("id").asText(), "Fix wording"));
        q.sort((a, b) -> Integer.compare((Integer) b.get("score"), (Integer) a.get("score")));

        int unitCount = 0, occupied = 0;
        for (JsonNode p : props) {
            unitCount += p.path("units").asInt();
            occupied += p.path("occupied").asInt();
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("queue", q);
        res.put("needsYou", q.size());
        res.put("urgent", q.stream().filter(t -> (Integer) t.get("score") >= 80).count());
        res.put("portfolio", Map.of("properties", props.size(), "units", unitCount, "occupied", occupied));
        if (automation != null && automation.has("runs_30d"))
            res.put("automation", Map.of("runs30d", automation.path("runs_30d").asInt(), "hoursSaved30d", automation.path("hours_saved_30d").asDouble(), "activeRules", automation.path("active_rules").asInt()));
        res.put("briefing", briefing(q, unitCount));
        return res;
    }

    private static Map<String, Object> task(String kind, String title, String subtitle, int score, double dollars, String path, String suggestion) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.put("subtitle", subtitle);
        m.put("score", Math.max(1, Math.min(100, score)));
        m.put("dollars", Math.round(dollars));
        m.put("path", path);
        m.put("suggestion", suggestion);
        return m;
    }

    private String briefing(List<Map<String, Object>> q, int units) {
        if (q.isEmpty()) return "Nothing needs you right now. Automation is handling the routine work across " + units + " units.";
        long urgent = q.stream().filter(t -> (Integer) t.get("score") >= 80).count();
        Map<String, Long> kinds = new LinkedHashMap<>();
        q.forEach(t -> kinds.merge(((String) t.get("kind")).toLowerCase(), 1L, Long::sum));
        String top = (String) q.get(0).get("title");
        return "You have " + q.size() + " items that need a person" + (urgent > 0 ? ", " + urgent + " urgent" : "") + ". Start with: " + top + ". The rest has been triaged, screened or assigned automatically.";
    }
}
