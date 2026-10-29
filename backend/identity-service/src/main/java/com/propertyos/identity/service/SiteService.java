package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;
import java.util.regex.Pattern;

/**
 * The public website's content: plans, features and the contact form.
 *
 * <p>What a plan includes comes from the plan definitions the product itself enforces, so the website cannot promise
 * a feature the plan does not give. The operator edits only how things are presented (wording, order, visibility, list price).</p>
 */
@Service
public class SiteService {
    /** Reasons a visitor can pick on the contact form. */
    public static final Set<String> TOPICS = new LinkedHashSet<>(List.of("DEMO", "PRICING", "SUPPORT", "PARTNERSHIP", "OTHER"));
    /** Where an enquiry stands. */
    public static final Set<String> STATUSES = new LinkedHashSet<>(List.of("NEW", "CONTACTED", "CLOSED"));
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");

    private final JdbcTemplate jdbc;
    private final ConfigClient config;
    private final PlanService plans;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public SiteService(JdbcTemplate jdbc, ConfigClient config, PlanService plans, AuditService audit, PlatformTransactionManager tm) {
        this.jdbc = jdbc;
        this.plans = plans;
        this.config = config;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    /**
     * Everything the public pages need in one call: visible plans (with the features each includes) and visible features.
     *
     * @return plans, features and the contact topics
     */
    public Map<String, Object> publicSite() {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(s -> {
            List<Map<String, Object>> features = jdbc.queryForList(
                    "select feature_key as key, title, summary, product from identity.site_feature where visible order by product, sort_order, title");
            Set<String> shown = new LinkedHashSet<>();
            features.forEach(f -> shown.add((String) f.get("key")));
            List<Map<String, Object>> out = new ArrayList<>();
            for (Map<String, Object> p : plans.list()) {
                if (!Boolean.TRUE.equals(p.get("visible"))) continue;
                @SuppressWarnings("unchecked") List<String> keys = (List<String>) p.get("features");
                boolean all = keys.contains("*");
                List<String> included = all ? new ArrayList<>(shown) : keys.stream().filter(shown::contains).toList();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", p.get("plan_key"));
                m.put("name", p.get("name"));
                m.put("tagline", p.get("tagline"));
                m.put("audience", p.get("audience"));
                m.put("highlight", p.get("highlight"));
                m.put("pricePerUnit", p.get("price_per_unit"));
                m.put("minimumMonthly", p.get("minimum_monthly"));
                m.put("allFeatures", all);
                m.put("features", included);
                out.add(m);
            }
            return Map.of("plans", out, "features", features, "topics", TOPICS);
        }));
    }

    /**
     * Stores a contact-form enquiry.
     *
     * @param body name, email, message (required) and company, phone, units, topic (optional); {@code website} is a trap field for bots
     * @throws ApiException (400) when a field is missing or too long
     */
    public void submitContact(Map<String, Object> body) {
        if (!text(body, "website").isEmpty()) return; // a person never fills the hidden field; pretend it worked
        String name = text(body, "name");
        String email = text(body, "email").toLowerCase();
        String message = text(body, "message");
        String topic = text(body, "topic").isEmpty() ? "DEMO" : text(body, "topic").toUpperCase();
        if (name.length() < 2 || name.length() > 120) throw ApiException.badRequest("Tell us your name (2 to 120 characters).");
        if (!EMAIL.matcher(email).matches() || email.length() > 200) throw ApiException.badRequest("Enter a valid email address so we can reply.");
        if (message.length() < 10 || message.length() > 2000) throw ApiException.badRequest("Your message needs 10 to 2,000 characters.");
        if (!TOPICS.contains(topic)) throw ApiException.badRequest("Topic must be one of " + TOPICS + ".");
        String company = text(body, "company");
        String phone = text(body, "phone");
        if (company.length() > 160 || phone.length() > 40) throw ApiException.badRequest("Company or phone is too long.");
        Integer units = null;
        Object u = body.get("units");
        if (u != null && !u.toString().isBlank()) {
            try {
                units = Integer.parseInt(u.toString().trim());
            } catch (NumberFormatException e) {
                throw ApiException.badRequest("Number of units must be a whole number.");
            }
            if (units < 0 || units > 1_000_000) throw ApiException.badRequest("Number of units must be between 0 and 1,000,000.");
        }
        Integer finalUnits = units;
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> jdbc.update(
                    "insert into identity.contact_request (id, name, email, company, phone, units, topic, message) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID(), name, email, company.isEmpty() ? null : company, phone.isEmpty() ? null : phone, finalUnits, topic, message));
            return null;
        });
    }

    /** All features for the operator, hidden ones included. */
    public List<Map<String, Object>> adminFeatures() {
        return jdbc.queryForList("select * from identity.site_feature order by product, sort_order, title");
    }

    /**
     * Changes how a feature is described on the website.
     *
     * @throws ApiException (404) for an unknown feature, (400) for a bad value
     */
    public List<Map<String, Object>> updateFeature(String key, Map<String, Object> body) {
        if (jdbc.queryForObject("select count(*) from identity.site_feature where feature_key = ?", Integer.class, key) == 0) throw ApiException.notFound("Feature not found.");
        if (body.containsKey("title")) {
            String t = text(body, "title");
            if (t.isEmpty() || t.length() > 100) throw ApiException.badRequest("The title needs 1 to 100 characters.");
            jdbc.update("update identity.site_feature set title = ?, updated_at = now() where feature_key = ?", t, key);
        }
        if (body.containsKey("summary")) {
            String t = text(body, "summary");
            if (t.isEmpty() || t.length() > 300) throw ApiException.badRequest("The summary needs 1 to 300 characters.");
            jdbc.update("update identity.site_feature set summary = ?, updated_at = now() where feature_key = ?", t, key);
        }
        if (body.containsKey("visible")) jdbc.update("update identity.site_feature set visible = ?, updated_at = now() where feature_key = ?", Boolean.TRUE.equals(body.get("visible")), key);
        audit.record("SiteFeature", UUID.nameUUIDFromBytes(key.getBytes()), "SITE_FEATURE_UPDATED", null, null, Map.of("feature", key, "by", UserContextHolder.require().name()));
        return adminFeatures();
    }

    /** Enquiries, newest first, optionally only one status. */
    public List<Map<String, Object>> contactRequests(String status) {
        if (status == null || status.isBlank()) return jdbc.queryForList("select * from identity.contact_request order by created_at desc limit 500");
        return jdbc.queryForList("select * from identity.contact_request where status = ? order by created_at desc limit 500", status.toUpperCase());
    }

    /**
     * Moves an enquiry to a new status.
     *
     * @throws ApiException (400) for an unknown status, (404) for an unknown enquiry
     */
    public void setContactStatus(UUID id, String status) {
        String s = status == null ? "" : status.toUpperCase();
        if (!STATUSES.contains(s)) throw ApiException.badRequest("Status must be one of " + STATUSES + ".");
        if (jdbc.update("update identity.contact_request set status = ?, updated_at = now() where id = ?", s, id) == 0) throw ApiException.notFound("Enquiry not found.");
        audit.record("ContactRequest", id, "CONTACT_STATUS_CHANGED", null, s, Map.of("by", UserContextHolder.require().name()));
    }

    private static String text(Map<String, Object> body, String key) {
        Object v = body.get(key);
        return v == null ? "" : v.toString().trim();
    }

    private static Double money(Object v, String label) {
        if (v == null || v.toString().isBlank()) return null; // back to the plan's own figure
        try {
            double d = Double.parseDouble(v.toString());
            if (d < 0 || d > 1_000_000) throw new NumberFormatException();
            return d;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest(label + " must be a number from 0 to 1,000,000.");
        }
    }
}
