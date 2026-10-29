package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.common.workflow.StateMachine;
import com.propertyos.common.workflow.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/** Listings: create, AI-assisted copy and pricing, compliance review, publish, pause. */
@Service
public class ListingService {
    /**
     * Workflow key for listings.
     */
    private static final String WF = "LISTING";
    /**
     * Phrases that must not appear in a listing because they signal discrimination.
     */
    private static final List<String> BANNED = List.of("no kids", "no children", "adults only", "christian", "english only",
            "no section 8", "perfect for singles", "no families", "able-bodied", "no wheelchairs", "young professionals only");

    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final EventPublisher events;
    private final AiClient ai;

    public ListingService(Db db, StateMachine sm, WorkflowEngine wf, EventPublisher events, AiClient ai) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.events = events;
        this.ai = ai;
    }

    /**
     * Lists listings with property and unit details.
     *
     * @param status workflow state to filter by, or null for all
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String base = """
                select l.*, p.name as property_name, p.city, p.state, u.number as unit_number, u.beds, u.baths, u.sqft, u.market_rent,
                       (select count(*) from leasing.application a where a.listing_id = l.id and a.status not in ('REJECTED','WITHDRAWN')) as applications,
                       (current_date - coalesce(u.vacant_since, current_date)) as days_vacant
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id""";
        return status == null ? db.list(base + " order by l.created_at desc")
                : db.list(base + " where l.status = ? order by l.created_at desc", status);
    }

    /**
     * Returns one listing with its property, unit and allowed actions.
     *
     * @param id the listing
     * @throws ApiException (404) when not found
     */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> l = new LinkedHashMap<>(db.one("Listing", """
                select l.*, p.name as property_name, p.street, p.city, p.state, u.number as unit_number, u.beds, u.baths, u.sqft, u.market_rent
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id where l.id = ?""", id));
        l.put("actions", wf.availableActions(WF, (String) l.get("status")));
        l.put("leads", db.list("select * from leasing.lead where listing_id = ? order by created_at desc", id));
        l.put("showings", db.list("select * from leasing.showing where listing_id = ? order by scheduled_at", id));
        l.put("applications", db.list("select id, applicant_name, status, credit_score, monthly_income, submitted_at from leasing.application where listing_id = ? order by submitted_at desc", id));
        return l;
    }

    /**
     * Creates a draft listing for a unit, pre-filled from the unit's rent and details.
     *
     * @param b unitId and optional title, description, rent
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        UUID unitId = Req.requiredUuid(b, "unitId");
        Map<String, Object> u = db.one("Unit", """
                select u.*, p.name as pname, p.city, p.state, p.amenities from leasing.unit u join leasing.property p on p.id = u.property_id where u.id = ?""", unitId);
        if (!"VACANT".equals(u.get("status"))) throw ApiException.conflict("Unit " + u.get("number") + " is occupied, so it can't be listed.");
        Long open = db.value(Long.class, "select count(*) from leasing.listing where unit_id = ? and status <> 'LEASED'", unitId);
        if (open != null && open > 0) throw ApiException.conflict("This unit already has an open listing.");
        BigDecimal rent = Optional.ofNullable(Req.money(b, "rent")).orElse((BigDecimal) u.get("market_rent"));
        BigDecimal deposit = Optional.ofNullable(Req.money(b, "deposit")).orElse(rent);
        String title = Optional.ofNullable(Req.str(b, "title")).orElse(titleFor(u));
        String desc = Optional.ofNullable(Req.str(b, "description")).orElse(fallbackCopy(u));
        UUID id = UUID.randomUUID();
        LocalDate avail = Optional.ofNullable(Req.date(b, "availableDate")).orElse(LocalDate.now().plusDays(14));
        db.update("""
                insert into leasing.listing (id, org_id, unit_id, property_id, owner_id, title, description, rent, deposit, available_date, photos, highlights, created_by)
                select ?, org_id, id, property_id, owner_id, ?, ?, ?, ?, ?, coalesce((select case when jsonb_array_length(photos) > 0 then photos else jsonb_build_array(photo_url) end from leasing.property where id = unit.property_id), '[]'::jsonb), '[]'::jsonb, ?
                from leasing.unit where id = ?""",
                id, title, desc, rent, deposit, avail, UserContextHolder.require().name(), unitId);
        sm.created("Listing", id, "DRAFT", Map.of("unit", u.get("number")));
        events.publish("ListingCreated", "Listing", id, Map.of("unitId", unitId.toString()));
        return Map.of("id", id);
    }

    /**
     * Edits a listing. Only drafts may be edited.
     *
     * @param id the listing
     * @param b the fields to change
     * @throws ApiException (409) when the listing is not a draft
     */
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> b) {
        Map<String, Object> l = db.one("Listing", "select status from leasing.listing where id = ?", id);
        if (!"DRAFT".equals(l.get("status")))
            throw ApiException.conflict("Edit a listing by moving it back to draft first.");
        db.update("""
                update leasing.listing set title = coalesce(?, title), description = coalesce(?, description), rent = coalesce(?, rent),
                       deposit = coalesce(?, deposit), available_date = coalesce(?, available_date), updated_at = now() where id = ?""",
                Req.str(b, "title"), Req.str(b, "description"), Req.money(b, "rent"), Req.money(b, "deposit"), Req.date(b, "availableDate"), id);
        return get(id);
    }

    /** AI-written listing copy and rent recommendation from comparable units; falls back to a template. */
    @Transactional
    public Map<String, Object> aiAssist(UUID id) {
        Map<String, Object> l = db.one("Listing", """
                select l.*, p.name as pname, p.city, p.state, p.amenities, u.number, u.beds, u.baths, u.sqft, u.market_rent, u.vacant_since
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id where l.id = ?""", id);
        BigDecimal comps = db.value(BigDecimal.class, """
                select coalesce(avg(rent_per_sqft), 0) from (
                  select u.current_rent / nullif(u.sqft, 0) as rent_per_sqft from leasing.unit u join leasing.property p on p.id = u.property_id
                  where p.city = ? and u.beds = ? and u.current_rent is not null) c""", l.get("city"), l.get("beds"));
        BigDecimal sqft = new BigDecimal(String.valueOf(l.get("sqft")));
        BigDecimal suggested = comps.signum() > 0 ? comps.multiply(sqft).setScale(0, RoundingMode.HALF_UP) : (BigDecimal) l.get("market_rent");
        long daysVacant = l.get("vacant_since") == null ? 0 : java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(l.get("vacant_since").toString()), LocalDate.now());
        if (daysVacant > 30) suggested = suggested.multiply(new BigDecimal("0.97")).setScale(0, RoundingMode.HALF_UP);
        BigDecimal current = (BigDecimal) l.get("rent");
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("task", "listing_copy");
        input.put("unit", l);
        input.put("comparableRentPerSqft", comps);
        input.put("daysVacant", daysVacant);
        Optional<JsonNode> out = ai.call("listing", input);
        String title = out.map(n -> n.path("title").asText(null)).orElse(null);
        String desc = out.map(n -> n.path("description").asText(null)).orElse(null);
        boolean aiWritten = desc != null && !desc.isBlank();
        if (!aiWritten) {
            desc = fallbackCopy(l);
        }
        Map<String, Object> rent = new LinkedHashMap<>();
        rent.put("suggested", suggested);
        rent.put("current", current);
        rent.put("comparableRentPerSqft", comps.setScale(2, RoundingMode.HALF_UP));
        rent.put("daysVacant", daysVacant);
        rent.put("monthlyDelta", suggested.subtract(current));
        rent.put("reason", comps.signum() > 0
                ? "Based on " + l.get("city") + " " + l.get("beds") + "-bed units currently leased at $" + comps.setScale(2, RoundingMode.HALF_UP) + " per sq ft"
                + (daysVacant > 30 ? ", reduced 3% because the unit has been vacant over 30 days." : ".")
                : "No local comparables yet, so the unit's market rent is kept.");
        db.update("update leasing.listing set title = coalesce(?, title), description = ?, ai_written = ?, rent_suggestion = ?::jsonb, updated_at = now() where id = ?",
                title, desc, aiWritten, db.json(rent), id);
        return get(id);
    }

    /**
     * Applies a workflow action (submit, approve, publish and so on) to a listing.
     *
     * @param id     the listing
     * @param action the workflow action
     * @param reason why, when the action requires one
     */
    @Transactional
    public Map<String, Object> act(UUID id, String action, String reason) {
        Map<String, Object> l = db.one("Listing", "select * from leasing.listing where id = ?", id);
        if ("SUBMIT".equals(action)) {
            sm.apply("Listing", "leasing.listing", id, WF, "SUBMIT", reason, null, null);
            Review r = review(l);
            db.update("update leasing.listing set ai_review = ?::jsonb where id = ?", db.json(r.toMap()), id);
            if (r.passed()) {
                sm.applyAsSystem("Listing", "leasing.listing", id, WF, "AI_PASS", "Compliance review passed", null);
                db.update("update leasing.listing set published_at = now() where id = ?", id);
                events.publish("ListingPublished", "Listing", id, Map.of("unitId", l.get("unit_id").toString()));
            } else {
                sm.applyAsSystem("Listing", "leasing.listing", id, WF, "AI_FLAG", "Compliance review flagged " + r.issues().size() + " issue(s)", null);
            }
            return get(id);
        }
        StateMachine.Outcome o = sm.apply("Listing", "leasing.listing", id, WF, action, reason, null, null);
        if ("LIVE".equals(o.to()) && "OVERRIDE".equals(action))
            db.update("update leasing.listing set published_at = now() where id = ?", id);
        if ("LEASED".equals(o.to())) events.publish("ListingLeased", "Listing", id, Map.of());
        return get(id);
    }

    record Review(boolean passed, List<String> issues, int score) {
        Map<String, Object> toMap() {
            return Map.of("passed", passed, "issues", issues, "score", score, "checkedAt", java.time.Instant.now().toString());
        }
    }

    /** Fair-housing and completeness check on listing copy. */
    private Review review(Map<String, Object> l) {
        List<String> issues = new ArrayList<>();
        String text = (l.get("title") + " " + l.get("description")).toLowerCase();
        for (String b : BANNED)
            if (text.contains(b)) issues.add("Wording \"" + b + "\" may violate fair housing rules. Describe the home, not who should live in it.");
        if (l.get("description") == null || l.get("description").toString().length() < 40)
            issues.add("Add a longer description (at least 40 characters).");
        if (new BigDecimal(l.get("rent").toString()).signum() <= 0) issues.add("Rent must be greater than zero.");
        if (l.get("photos") == null || l.get("photos").toString().equals("[]")) issues.add("Add at least one photo.");
        return new Review(issues.isEmpty(), issues, Math.max(0, 100 - issues.size() * 25));
    }

    private static String titleFor(Map<String, Object> u) {
        int beds = Integer.parseInt(String.valueOf(u.get("beds")));
        return (beds == 0 ? "Studio" : beds + "-bedroom") + " at " + u.getOrDefault("pname", "") + ", " + u.get("city") + " " + u.get("state");
    }

    private static String fallbackCopy(Map<String, Object> u) {
        int beds = Integer.parseInt(String.valueOf(u.get("beds")));
        return "Unit " + (u.containsKey("number") ? u.get("number") : "") + " offers " + u.get("sqft") + " sq ft with "
                + (beds == 0 ? "an open studio layout" : beds + " bedrooms") + " and " + u.get("baths") + " baths in " + u.get("city") + ". "
                + "Schedule a showing or apply online today.";
    }
}
