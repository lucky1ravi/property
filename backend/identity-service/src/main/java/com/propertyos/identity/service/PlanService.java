package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The plans companies can be on: price, which features each includes, and how it is shown on the website.
 *
 * <p>The features a plan includes are what the product enforces for every company on that plan, so editing a plan
 * changes what those companies can use straight away.</p>
 */
@Service
public class PlanService {
    private static final Pattern KEY = Pattern.compile("^[A-Z][A-Z0-9_]{1,29}$");

    private final JdbcTemplate jdbc;
    private final ConfigClient config;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final EventPublisher events;

    public PlanService(JdbcTemplate jdbc, ConfigClient config, ObjectMapper mapper, AuditService audit, EventPublisher events) {
        this.jdbc = jdbc;
        this.config = config;
        this.mapper = mapper;
        this.audit = audit;
        this.events = events;
    }

    /** All plans in display order, each with the number of companies on it and its features as a list ({@code ["*"]} means all). */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select p.*, (select count(*) from identity.organization o where o.plan = p.plan_key) as company_count from identity.plan p order by p.sort_order, p.name");
        rows.forEach(r -> r.put("features", featureList((String) r.get("features"))));
        return rows;
    }

    /** True when a plan with this key exists. */
    public boolean exists(String key) {
        return key != null && jdbc.queryForObject("select count(*) from identity.plan where plan_key = ?", Integer.class, key) > 0;
    }

    /** The first plan in display order, used when onboarding does not name one. */
    public String defaultPlan() {
        List<String> keys = jdbc.queryForList("select plan_key from identity.plan order by (plan_key = 'GROWTH') desc, sort_order limit 1", String.class);
        if (keys.isEmpty()) throw ApiException.badRequest("There are no plans yet. Create a plan first.");
        return keys.get(0);
    }

    /** Plans in the shape the configuration expects: {@code {KEY: {pricePerUnit, minimum, features: [..]}}}. */
    public ObjectNode asConfig() {
        ObjectNode out = mapper.createObjectNode();
        for (Map<String, Object> r : list()) {
            ObjectNode p = out.putObject((String) r.get("plan_key"));
            p.put("pricePerUnit", ((Number) r.get("price_per_unit")).doubleValue());
            p.put("minimum", ((Number) r.get("minimum_monthly")).doubleValue());
            p.set("features", mapper.valueToTree(r.get("features")));
        }
        return out;
    }

    /**
     * Creates a plan.
     *
     * @param body key, name, price per unit, monthly minimum, features and optional wording
     * @return all plans
     * @throws ApiException (400) for a bad value, (409) when the key is taken
     */
    public List<Map<String, Object>> create(Map<String, Object> body) {
        String key = text(body, "key").toUpperCase().replace(' ', '_');
        if (!KEY.matcher(key).matches()) throw ApiException.badRequest("The plan key needs 2 to 30 capital letters, digits or underscores, starting with a letter.");
        if (exists(key)) throw ApiException.conflict("A plan with the key " + key + " already exists.");
        String name = text(body, "name");
        if (name.length() < 2 || name.length() > 60) throw ApiException.badRequest("The plan name needs 2 to 60 characters.");
        List<String> features = features(body.get("features"));
        jdbc.update("insert into identity.plan (plan_key, name, tagline, audience, visible, price_per_unit, minimum_monthly, features, sort_order) values (?,?,?,?,?,?,?,?, " +
                        "(select coalesce(max(sort_order), 0) + 1 from identity.plan))",
                key, name, limited(body, "tagline", 200), limited(body, "audience", 160), !Boolean.FALSE.equals(body.get("visible")),
                money(body.get("pricePerUnit"), "Price per unit"), money(body.get("minimumMonthly"), "Monthly minimum"), toJson(features));
        if (Boolean.TRUE.equals(body.get("highlight"))) highlight(key);
        record(key, "PLAN_CREATED");
        return list();
    }

    /**
     * Changes a plan. Only the fields present in {@code body} change. A change to its features reaches every company on the plan immediately.
     *
     * @throws ApiException (404) for an unknown plan, (400) for a bad value
     */
    public List<Map<String, Object>> update(String key, Map<String, Object> body) {
        if (!exists(key)) throw ApiException.notFound("Plan not found.");
        if (body.containsKey("name")) {
            String n = text(body, "name");
            if (n.length() < 2 || n.length() > 60) throw ApiException.badRequest("The plan name needs 2 to 60 characters.");
            set(key, "name", n);
        }
        if (body.containsKey("tagline")) set(key, "tagline", limited(body, "tagline", 200));
        if (body.containsKey("audience")) set(key, "audience", limited(body, "audience", 160));
        if (body.containsKey("visible")) set(key, "visible", Boolean.TRUE.equals(body.get("visible")));
        if (body.containsKey("pricePerUnit")) set(key, "price_per_unit", money(body.get("pricePerUnit"), "Price per unit"));
        if (body.containsKey("minimumMonthly")) set(key, "minimum_monthly", money(body.get("minimumMonthly"), "Monthly minimum"));
        if (body.containsKey("sortOrder")) set(key, "sort_order", Integer.parseInt(body.get("sortOrder").toString()));
        if (body.containsKey("highlight")) {
            if (Boolean.TRUE.equals(body.get("highlight"))) highlight(key);
            else set(key, "highlight", false);
        }
        if (body.containsKey("features")) {
            set(key, "features", toJson(features(body.get("features"))));
            refreshCompanies(key);
        }
        record(key, "PLAN_UPDATED");
        return list();
    }

    /**
     * Deletes a plan that no company is on.
     *
     * @throws ApiException (404) for an unknown plan, (409) when companies are still on it or it is the last plan
     */
    public List<Map<String, Object>> delete(String key) {
        if (!exists(key)) throw ApiException.notFound("Plan not found.");
        int companies = jdbc.queryForObject("select count(*) from identity.organization where plan = ?", Integer.class, key);
        if (companies > 0) throw ApiException.conflict(companies + (companies == 1 ? " company is" : " companies are") + " on this plan. Move them to another plan first.");
        if (jdbc.queryForObject("select count(*) from identity.plan", Integer.class) <= 1) throw ApiException.conflict("You can't delete the last plan.");
        jdbc.update("delete from identity.plan where plan_key = ?", key);
        record(key, "PLAN_DELETED");
        return list();
    }

    // ---------------- helpers ----------------
    private void refreshCompanies(String key) {
        for (UUID org : jdbc.queryForList("select id from identity.organization where plan = ?", UUID.class, key)) {
            config.invalidate(org);
            events.publish("ConfigurationChanged", "Organization", org, Map.of("section", "plan", "version", 0));
        }
    }

    private void highlight(String key) {
        jdbc.update("update identity.plan set highlight = (plan_key = ?), updated_at = now()", key); // one plan is "most popular"
    }

    private void set(String key, String column, Object value) {
        jdbc.update("update identity.plan set " + column + " = ?, updated_at = now() where plan_key = ?", value, key);
    }

    private void record(String key, String action) {
        audit.record("Plan", UUID.nameUUIDFromBytes(key.getBytes()), action, null, null, Map.of("plan", key, "by", UserContextHolder.require().name()));
    }

    private List<String> features(Object raw) {
        if (!(raw instanceof Collection<?> c)) throw ApiException.badRequest("Features must be a list.");
        Set<String> known = new HashSet<>();
        config.defaults().path("features").fieldNames().forEachRemaining(known::add);
        List<String> out = new ArrayList<>();
        for (Object o : c) {
            String f = String.valueOf(o);
            if (!"*".equals(f) && !known.contains(f)) throw ApiException.badRequest("Unknown feature: " + f);
            if (!out.contains(f)) out.add(f);
        }
        if (out.contains("*")) return List.of("*");
        return out;
    }

    private List<String> featureList(String json) {
        try {
            List<String> out = new ArrayList<>();
            JsonNode n = mapper.readTree(json);
            n.forEach(x -> out.add(x.asText()));
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private String toJson(List<String> features) {
        try {
            return mapper.writeValueAsString(features);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static String limited(Map<String, Object> body, String key, int max) {
        String t = text(body, key);
        if (t.length() > max) throw ApiException.badRequest("The " + key + " is too long (at most " + max + " characters).");
        return t;
    }

    private static double money(Object v, String label) {
        if (v == null || v.toString().isBlank()) return 0;
        try {
            double d = Double.parseDouble(v.toString());
            if (d < 0 || d > 1_000_000) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label + " must be a number from 0 to 1,000,000.");
        }
    }
}
