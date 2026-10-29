package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task-specific AI tools for staff. Each tool reads only what the signed-in person may see (their own token is used upstream),
 * asks the model for structured JSON, checks the answer against the source data, and falls back to plain rules if the model
 * is offline or its answer fails the checks. The result says which one produced it ("AI" or "RULES").
 * Text work uses the configured chat model; picture work uses the image model.
 */
@Service
public class Studio {
    private static final Logger log = LoggerFactory.getLogger(Studio.class);
    private final Upstream up;
    private final LlmProvider provider;
    private final Guardrails guardrails;
    private final ObjectMapper mapper;
    private final ConfigClient config;
    private final AiAudit audit;

    public Studio(Upstream up, LlmProvider provider, Guardrails guardrails, ObjectMapper mapper, ConfigClient config, AiAudit audit) {
        this.up = up;
        this.provider = provider;
        this.guardrails = guardrails;
        this.mapper = mapper;
        this.config = config;
        this.audit = audit;
    }

    private void enabled(String key) {
        if (!config.flag("ai/" + key + "/enabled", true))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "This AI tool is turned off for your company.");
    }

    /** Asks the chat model for JSON. Returns null when no model is configured or it didn't return usable JSON. */
    private JsonNode ask(String tool, String system, String user) {
        Optional<Llm> m = provider.get();
        if (m.isEmpty()) return null;
        long t0 = System.currentTimeMillis();
        try {
            Llm.Reply r = m.get().chat(system + " Reply with one JSON object only. Use only facts in the input; never invent numbers, dates or names.", List.of(Llm.Msg.user(guardrails.redact(user))), List.of(), true);
            JsonNode out = mapper.readTree(strip(r.text()));
            audit.record("STUDIO", tool, user, List.of(), m.get().provider() + ":" + m.get().model(), r.inputTokens(), r.outputTokens(), false, null, false, System.currentTimeMillis() - t0);
            return out;
        } catch (Exception e) {
            log.warn("{} model call failed, using rules: {}", tool, e.getMessage());
            audit.record("STUDIO", tool, user, List.of(), m.get().provider(), 0, 0, false, e.getMessage(), true, System.currentTimeMillis() - t0);
            return null;
        }
    }

    private static String strip(String s) {
        String t = s == null ? "" : s.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t;
    }

    private String modelName() {
        return provider.get().map(l -> l.provider() + ":" + l.model()).orElse("rules");
    }

    private static JsonNode arr(JsonNode n) {
        return n == null || !n.isArray() ? new ObjectMapper().createArrayNode() : n;
    }

    private static double d(JsonNode n, String f) {
        return n.path(f).asDouble(0);
    }

    private static String usd(double v) {
        return String.format("$%,.2f", v);
    }

    // =============================================================== Lease abstract
    public Map<String, Object> leaseAbstract(String bearer, String leaseId) {
        enabled("LEASE_ABSTRACT");
        JsonNode l = up.get(bearer, "/api/leasing/leases/" + leaseId);
        String text = l.path("document_text").asText("");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("lease", Map.of("id", leaseId, "tenant", l.path("tenant_name").asText(""), "unit", l.path("unit_label").asText(""), "property", l.path("property_name").asText("")));
        JsonNode ai = text.isBlank() ? null : ask("lease-abstract",
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
            out.put("model", modelName());
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
        } catch (Exception e) {
            return false;
        }
    }

    // =============================================================== Anomaly scan
    public Map<String, Object> anomalies(String bearer) {
        enabled("ANOMALY_SCAN");
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
            JsonNode ai = ask("anomaly-summary", "You help an accountant at a property management company. Given flagged billing items, return {\"summary\": 2 sentences saying what to check first and why, \"priority\": array of the item titles in the order to check}. Do not add items.", in.toString());
            if (ai != null && ai.path("summary").isTextual() && !ai.path("summary").asText().isBlank()) {
                out.put("summary", ai.path("summary").asText());
                out.put("source", "AI+RULES");
                out.put("model", modelName());
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

    // =============================================================== Owner statement narrative
    private static final Pattern MONEY = Pattern.compile("\\$\\s?([0-9][0-9,]*(?:\\.[0-9]+)?)");

    public Map<String, Object> statementNarrative(String bearer, String id) {
        enabled("STATEMENT_NARRATIVE");
        JsonNode s = up.get(bearer, "/api/billing/statements/" + id);
        Set<String> allowed = new HashSet<>();
        for (String f : List.of("rent_collected", "fees_collected", "mgmt_fee", "expenses", "net_payout")) allowed.add(norm(d(s, f)));
        for (JsonNode ln : arr(s.path("lines"))) allowed.add(norm(d(ln, "amount")));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("statement", Map.of("id", id, "owner", s.path("owner_name").asText(""), "period", s.path("period").asText(""), "status", s.path("status").asText("")));
        JsonNode ai = ask("statement-narrative", "You write the short explanation that goes at the top of a property owner's monthly statement. Return {\"headline\": under 12 words, \"narrative\": 70-110 words in a warm professional tone, \"watch\": array of up to 3 short items the owner may ask about}. "
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
                out.put("model", modelName());
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

    // =============================================================== Resident outlook
    public Map<String, Object> residentOutlook(String bearer) {
        enabled("RESIDENT_OUTLOOK");
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
            JsonNode ai = ask("resident-outlook", "You help a property manager plan outreach. For each resident return one short, kind, practical next step. Return {\"plans\": {\"<leaseId>\": \"one sentence\"}}. Never suggest anything about the person's background, family or protected characteristics, and never suggest threats or eviction.", in.toString());
            if (ai != null && ai.path("plans").isObject()) {
                int used = 0;
                for (Map<String, Object> r : top) {
                    String p = ai.path("plans").path(String.valueOf(r.get("leaseId"))).asText("");
                    if (!p.isBlank() && guardrails.fairHousing(p).isEmpty()) { r.put("action", p); used++; }
                }
                if (used > 0) { out.put("source", "AI+RULES"); out.put("model", modelName()); }
            }
        }
        out.put("note", "Scores are simple, explainable rules on payments, repairs, lease dates and market rent. They help you decide who to contact first. They are not a reason to refuse or end a lease.");
        return out;
    }

    // =============================================================== Maintenance photo triage
    public Map<String, Object> photoTriage(byte[] image, String mime, String note) {
        enabled("PHOTO_TRIAGE");
        Llm v = provider.vision().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_OFFLINE", "Photo analysis needs an image model, and none is connected."));
        long t0 = System.currentTimeMillis();
        try {
            String reply = v.vision("You are a maintenance coordinator for a US property management company. You look at one photo of a problem in a rental home. Reply with JSON only: "
                            + "{\"category\": one of PLUMBING, HVAC, ELECTRICAL, APPLIANCE, PEST, GENERAL, \"priority\": one of EMERGENCY, HIGH, NORMAL, LOW, \"safetyRisk\": boolean, "
                            + "\"summary\": one sentence describing what you can see, \"likelyCause\": short, \"suggestedAction\": short, \"estimateLow\": number, \"estimateHigh\": number (typical US repair cost in dollars), "
                            + "\"tenantAdvice\": one safe thing the resident can do right now, \"confidence\": number 0 to 1}. Active water, sparking, fire, gas, exposed wiring or structural danger is EMERGENCY. "
                            + "If the photo does not show a home maintenance problem, use category GENERAL, priority LOW and say so in the summary. Never identify people.",
                    "Triage this photo." + (note == null || note.isBlank() ? "" : " Resident note: " + guardrails.redact(trim(note, 400))), image, mime);
            JsonNode j = mapper.readTree(strip(reply));
            ObjectNode o = mapper.createObjectNode();
            String cat = j.path("category").asText("GENERAL").toUpperCase(), pri = j.path("priority").asText("NORMAL").toUpperCase();
            o.put("category", List.of("PLUMBING", "HVAC", "ELECTRICAL", "APPLIANCE", "PEST", "GENERAL").contains(cat) ? cat : "GENERAL");
            o.put("priority", List.of("EMERGENCY", "HIGH", "NORMAL", "LOW").contains(pri) ? pri : "NORMAL");
            o.put("safetyRisk", j.path("safetyRisk").asBoolean(false));
            o.put("summary", j.path("summary").asText("Could not describe the photo."));
            o.put("likelyCause", j.path("likelyCause").asText(""));
            o.put("suggestedAction", j.path("suggestedAction").asText(""));
            double lo = Math.max(0, j.path("estimateLow").asDouble(0)), hi = Math.max(lo, j.path("estimateHigh").asDouble(lo));
            o.put("estimateLow", Math.round(lo));
            o.put("estimateHigh", Math.round(Math.min(hi, 50000)));
            o.put("tenantAdvice", j.path("tenantAdvice").asText(""));
            double conf = j.path("confidence").asDouble(0.5);
            o.put("confidence", Math.max(0, Math.min(1, conf > 1 ? conf / 100 : conf)));
            o.put("source", "AI");
            o.put("model", v.provider() + ":" + v.model());
            o.put("note", "An estimate from one photo. A person should confirm before any work is approved.");
            audit.record("STUDIO", "photo-triage", "image " + image.length + " bytes", List.of(), v.provider() + ":" + v.model(), 0, 0, false, null, false, System.currentTimeMillis() - t0);
            return mapper.convertValue(o, Map.class);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Photo triage failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "The photo couldn't be analysed. Try a clearer photo or try again.");
        }
    }

    // =============================================================== Fair housing check
    public Map<String, Object> fairHousing(String text) {
        enabled("FAIR_HOUSING_CHECK");
        if (text == null || text.isBlank()) throw ApiException.badRequest("Paste the text you want checked.");
        if (text.length() > 4000) throw ApiException.badRequest("Keep the text under 4,000 characters.");
        List<String> hits = guardrails.fairHousing(text);
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode ai = ask("fair-housing", "You review rental ads and messages for US Fair Housing Act problems (race, color, religion, sex, disability, familial status, national origin, plus common state classes such as source of income). "
                + "Return {\"issues\": [{\"phrase\": exact words from the text, \"why\": one sentence, \"protectedClass\": string}], \"rewrite\": the same text rewritten to remove the problems without changing the facts, \"verdict\": \"CLEAR\" or \"REVIEW\"}. "
                + "Describing the home is fine; describing who should live there is not. Reasonable accommodation language is fine.", text);
        List<Map<String, String>> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String h : hits) { issues.add(Map.of("phrase", h, "why", "This wording is on the list of phrases that are commonly treated as discriminatory.", "protectedClass", "Listed phrase")); seen.add(h.toLowerCase()); }
        String rewrite = null;
        if (ai != null && ai.path("issues").isArray()) {
            for (JsonNode i : ai.path("issues")) {
                String p = i.path("phrase").asText("");
                if (!p.isBlank() && text.toLowerCase().contains(p.toLowerCase()) && seen.add(p.toLowerCase()))
                    issues.add(Map.of("phrase", p, "why", i.path("why").asText(""), "protectedClass", i.path("protectedClass").asText("")));
            }
            rewrite = ai.path("rewrite").asText(null);
            if (rewrite != null && !guardrails.fairHousing(rewrite).isEmpty()) rewrite = null;
            out.put("source", "AI+RULES");
            out.put("model", modelName());
        } else out.put("source", "RULES");
        out.put("verdict", issues.isEmpty() ? "CLEAR" : "REVIEW");
        out.put("issues", issues);
        out.put("rewrite", issues.isEmpty() ? null : rewrite);
        out.put("note", "A helper, not legal advice. Have a person approve anything that goes public.");
        return out;
    }

    private static String trim(String s, int n) {
        return s == null ? "" : s.length() <= n ? s : s.substring(0, n);
    }
}
