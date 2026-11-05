package com.propertyos.leasing.service;

import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Public (signed-out) access: published listings, enquiries, showing requests and applications. Runs as the system with explicit filters. */
@Service
public class PublicService {
    private final Db db;
    private final EventPublisher events;
    private final LeadService leads;
    private final TransactionTemplate tx;
    private final com.propertyos.common.security.SignedLinks links;

    private final PublicCatalog catalog;
    private final ReviewModeration moderation;

    private final CampaignLookup campaigns;

    public PublicService(Db db, EventPublisher events, LeadService leads, PlatformTransactionManager tm, com.propertyos.common.security.SignedLinks links, PublicCatalog catalog,
                         ReviewModeration moderation, CampaignLookup campaigns) {
        this.campaigns = campaigns;
        this.catalog = catalog;
        this.moderation = moderation;
        this.links = links;
        this.tx = new TransactionTemplate(tm);
        this.db = db;
        this.events = events;
        this.leads = leads;
    }

    private com.propertyos.common.security.SuspendedOrgs suspended;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuspended(com.propertyos.common.security.SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    /** Companies PropertyOS has suspended, as a comma-separated list: their homes are not shown or open to applications. */
    String suspendedCsv() {
        return suspended == null ? "" : suspended.asCsv();
    }

    private <T> T asSystem(java.util.function.Supplier<T> work) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(st -> work.get()));
    }

    /**
     * Public: returns one live listing.
     *
     * @throws ApiException (404) when it is not live
     */
    public Map<String, Object> listing(UUID id) {
        return asSystem(() -> {
            Map<String, Object> l = findLive(id);
            db.update("update leasing.listing set views = views + 1 where id = ?", id);
            // Who is renting it out, the other homes in the building and the amenity icons; the internal references never leave the service.
            UUID org = UUID.fromString(String.valueOf(l.remove("org_ref")));
            UUID property = UUID.fromString(String.valueOf(l.remove("property_ref")));
            l.putAll(catalog.detailExtras(id, org, property, l.get("amenities")));
            return l;
        });
    }

    /**
     * Public: what residents say about the home and its building: for the property and for the unit, the average, how the stars are spread
     * and the latest written comments. Names are shortened ("Rae R.") and nothing else about the resident is shown.
     *
     * @throws ApiException (404) when the listing is not live
     */
    public Map<String, Object> reviews(UUID listingId) {
        return asSystem(() -> {
            Map<String, Object> l = db.one("Listing", "select l.property_id, l.unit_id, l.org_id from leasing.listing l where l.id = ? and l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))",
                    listingId, suspendedCsv());
            Map<String, Object> out = new LinkedHashMap<>();
            String company = String.valueOf(catalog.branding((UUID) l.get("org_id")).get("name"));
            int min = moderation.minScore((UUID) l.get("org_id"));
            out.put("property", reviewSummary("PROPERTY", (UUID) l.get("property_id"), company, min));
            out.put("unit", reviewSummary("UNIT", (UUID) l.get("unit_id"), company, min));
            return out;
        });
    }

    private Map<String, Object> reviewSummary(String type, UUID subject, String company, int minScore) {
        Map<String, Object> s = db.list("select count(*) as count, coalesce(round(avg(stars), 1), 0) as average from leasing.review where subject_type = ? and subject_id = ?", type, subject).get(0);
        Map<Integer, Long> distribution = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 1; i <= 5; i++) distribution.put(i, 0L);
        for (Map<String, Object> r : db.list("select stars, count(*) as n from leasing.review where subject_type = ? and subject_id = ? group by stars", type, subject))
            distribution.put(((Number) r.get("stars")).intValue(), ((Number) r.get("n")).longValue());
        List<Map<String, Object>> comments = new ArrayList<>();
        // Only comments the AI scored at or above the minimum are shown. Older comments with no score yet are scored here, the first time they would be shown.
        for (Map<String, Object> r : db.list("""
                select id, rater_name, stars, comment, created_at, reply, replied_at, ai_score, moderation_override from leasing.review
                where subject_type = ? and subject_id = ? and comment is not null and btrim(comment) <> ''
                  and (moderation_override = 'SHOW' or (moderation_override is null and (ai_score is null or ai_score >= ?)))
                order by created_at desc limit 40""", type, subject, minScore)) {
            if (comments.size() >= 20) break;
            Integer score = r.get("ai_score") == null ? null : ((Number) r.get("ai_score")).intValue();
            String override = (String) r.get("moderation_override");
            if (score == null && override == null) {
                ReviewModeration.Result mod = moderation.score(String.valueOf(r.get("comment")));
                db.update("update leasing.review set ai_score = ?, ai_source = ?, ai_reasons = ? where id = ?", mod.score(), mod.source(), String.join(" ", mod.reasons()), r.get("id"));
                score = mod.score();
            }
            if (!ReviewModeration.visible(score, override, minScore)) continue;
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("name", ListingRanking.publicName((String) r.get("rater_name")));
            c.put("stars", r.get("stars"));
            c.put("comment", r.get("comment"));
            c.put("created_at", r.get("created_at"));
            // The reply is signed with the company's public name, never with the staff member's.
            if (r.get("reply") != null) { c.put("reply", r.get("reply")); c.put("reply_by", company); c.put("replied_at", r.get("replied_at")); }
            comments.add(c);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("count", s.get("count"));
        out.put("average", s.get("average"));
        out.put("distribution", distribution);
        out.put("comments", comments);
        return out;
    }

    Map<String, Object> findLive(UUID id) {
        return db.one("Listing", """
                select l.id, l.org_id as org_ref, l.property_id as property_ref, l.title, l.description, l.rent, l.deposit, l.available_date, l.photos, l.highlights,
                       p.name as property_name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.amenities, u.beds, u.baths, u.sqft,
                       p.type as property_type, p.year_built, u.number as unit_number, u.features,
                       (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating,
                       (select count(*) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating_count,
                       exists (select 1 from leasing.listing_boost bo where bo.listing_id = l.id and bo.status = 'ACTIVE' and bo.starts_at <= now() and bo.ends_at > now()) as sponsored,
                       (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'UNIT' and r.subject_id = u.id) as unit_rating,
                       (select count(*) from leasing.review r where r.subject_type = 'UNIT' and r.subject_id = u.id) as unit_rating_count
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                where l.id = ? and l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))""", id, suspendedCsv());
    }

    /**
     * Public: records an enquiry from the website as a lead.
     */
    public Map<String, Object> enquire(UUID listingId, Map<String, Object> body) {
        return asSystem(() -> leads.enquire(listingId, body, "WEBSITE"));
    }

    /**
     * Public: books a showing requested from the website.
     */
    public Map<String, Object> requestShowing(UUID listingId, Map<String, Object> body) {
        return asSystem(() -> leads.book(listingId, body, true));
    }

    /**
     * Public: stores a rental application and returns a signed token the applicant uses to upload documents.
     */
    public Map<String, Object> apply(UUID listingId, Map<String, Object> b) {
        return asSystem(() -> doApply(listingId, b));
    }

    Map<String, Object> doApply(UUID listingId, Map<String, Object> b) {
        return doApply(listingId, b, "WEBSITE", null, true);
    }

    /**
     * Stores an application. {@code source} says where it came from (WEBSITE, or a campaign's channel) and {@code campaignId} which campaign, if any; a website
     * tracking code ({@code campaign} in the body) supplies the campaign when none is given. {@code announce} is false when the caller sends its own notice.
     */
    Map<String, Object> doApply(UUID listingId, Map<String, Object> b, String source, UUID campaignId, boolean announce) {
        Map<String, Object> li = db.one("Listing", """
                select l.*, u.number, p.name as pname from leasing.listing l join leasing.unit u on u.id = l.unit_id
                join leasing.property p on p.id = l.property_id where l.id = ? and l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))""", listingId, suspendedCsv());
        String first = Req.required(b, "firstName");
        String last = Req.required(b, "lastName");
        String email = Req.email(b, "email");
        if (!Boolean.TRUE.equals(b.get("consent")))
            throw ApiException.badRequest("Consent to a credit and background check is required to apply.");
        UUID orgId = (UUID) li.get("org_id");
        Optional<Map<String, Object>> dup = db.find("""
                select id from leasing.application where listing_id = ? and lower(applicant_email) = lower(?) and status not in ('REJECTED','WITHDRAWN')""", listingId, email);
        if (dup.isPresent()) throw ApiException.conflict("You've already applied for this home.");
        UUID personId = db.find("select id from leasing.person where org_id = ? and lower(email) = lower(?)", orgId, email)
                .map(m -> (UUID) m.get("id")).orElse(null);
        if (personId == null) {
            personId = UUID.randomUUID();
            db.update("insert into leasing.person (id, org_id, first_name, last_name, email, phone, annual_income, employer, months_employed) values (?,?,?,?,?,?,?,?,?)",
                    personId, orgId, first, last, email, Req.str(b, "phone"),
                    Optional.ofNullable(Req.money(b, "monthlyIncome")).map(m -> m.multiply(java.math.BigDecimal.valueOf(12))).orElse(null),
                    Req.str(b, "employer"), Req.integer(b, "monthsEmployed"));
        }
        UUID id = UUID.randomUUID();
        UUID campaign = campaignId != null ? campaignId : campaigns.forTrackingCode(Req.str(b, "campaign"), orgId);
        db.update("""
                insert into leasing.application (id, org_id, listing_id, unit_id, property_id, owner_id, applicant_id, applicant_name, applicant_email, unit_label,
                    status, monthly_income, employer, months_employed, current_rent, landlord_reference, occupants, pets, move_in_date, documents, fee_paid, source, campaign_id)
                values (?,?,?,?,?,?,?,?,?,?,'SUBMITTED',?,?,?,?,?,?,?,?,?::jsonb,true,?,?)""",
                id, orgId, listingId, li.get("unit_id"), li.get("property_id"), li.get("owner_id"), personId, first + " " + last, email,
                li.get("pname") + " " + li.get("number"), Req.money(b, "monthlyIncome"), Req.str(b, "employer"), Req.integer(b, "monthsEmployed"),
                Req.money(b, "currentRent"), Req.str(b, "landlordReference"), Optional.ofNullable(Req.integer(b, "occupants")).orElse(1),
                Req.str(b, "pets"), Req.date(b, "moveInDate"), db.json(Req.strings(b, "documents")), source, campaign);
        leads.ensureLead(li, first + " " + last, email, Req.str(b, "phone"), "Submitted an application.", source, campaign, 90, "APPLY");
        Map<String, Object> submitted = new HashMap<>();
        submitted.put("listingId", listingId.toString());
        submitted.put("applicantName", first + " " + last);
        submitted.put("applicantEmail", email);
        submitted.put("unit", li.get("number").toString());
        submitted.put("propertyName", String.valueOf(li.get("pname")));
        submitted.put("unitLabel", li.get("pname") + " " + li.get("number"));
        if (li.get("rent") != null) submitted.put("rent", li.get("rent").toString());
        submitted.put("source", source);
        if (Req.str(b, "phone") != null) submitted.put("applicantPhone", Req.str(b, "phone"));
        if (Req.money(b, "monthlyIncome") != null) submitted.put("monthlyIncome", Req.money(b, "monthlyIncome").toPlainString());
        if (Req.str(b, "employer") != null) submitted.put("employer", Req.str(b, "employer"));
        if (Req.date(b, "moveInDate") != null) submitted.put("moveInDate", Req.date(b, "moveInDate").toString());
        if (Req.integer(b, "occupants") != null) submitted.put("occupants", Req.integer(b, "occupants"));
        if (Req.str(b, "pets") != null) submitted.put("pets", Req.str(b, "pets"));
        events.publish(orgId, "ApplicationSubmitted", "Application", id, submitted);
        // The leasing team hears about it straight away; the applicant has no account, so this is the only way staff learn of a new application.
        if (announce) events.publish(orgId, "NotificationRequested", "Notification", UUID.randomUUID(), Map.of("audience", "ROLE", "target", "PROPERTY_MANAGER",
                "title", "New application: " + first + " " + last, "body", "Applied for " + li.get("pname") + " " + li.get("number") + ". Screening starts automatically.",
                "link", "/leasing/applications/" + id, "priority", "NORMAL", "category", "LEASING"));
        // A signed link lets the applicant (who has no account) add documents to this one application.
        return Map.of("id", id, "status", "SUBMITTED", "uploadToken", links.sign("application-upload", id),
                "message", "Application received. Upload your documents now so screening isn't held up. We'll email you as soon as screening is complete.");
    }
}
