package com.propertyos.leasing.service;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * What residents think of the property and the unit they live in. Only a resident who has a lease on the property (or the unit) can
 * rate it, once each, and can change their mind. Company staff and the owner read the ratings on their properties; a resident reads
 * only their own. Nobody else can rate: staff run the property, so rating it themselves would prove nothing.
 */
@Service
public class ReviewService {

    private final Db db;
    private final AuditService audit;
    private final ReviewModeration moderation;
    private final com.propertyos.common.config.ConfigClient config;

    public ReviewService(Db db, AuditService audit, ReviewModeration moderation, com.propertyos.common.config.ConfigClient config) {
        this.db = db;
        this.audit = audit;
        this.moderation = moderation;
        this.config = config;
    }

    /** Public replies and the show/hide decision are the REVIEWS plan feature: a company whose plan leaves it out cannot use them. */
    private void requireReviewTools() {
        if (!config.featureEnabled("REVIEWS")) throw ApiException.forbidden("Replying to reviews isn't included in your company's plan. Ask PropertyOS about adding it.");
    }

    private static UserContext resident() {
        UserContext ctx = UserContextHolder.require();
        if (!UserContext.SCOPE_TENANT.equals(ctx.scope())) throw ApiException.forbidden("Only residents can rate the home they live in.");
        return ctx;
    }

    /** The resident's home as they can rate it: the property and the unit of their latest lease, each with their rating if they gave one. */
    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        UserContext ctx = resident();
        List<Map<String, Object>> leases = db.list("""
                select property_id, unit_id, property_name, unit_label, status from leasing.lease
                where tenant_id = ? and status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN','ENDED') order by start_date desc limit 1""", ctx.partyId());
        Map<String, Object> out = new LinkedHashMap<>();
        if (leases.isEmpty()) { out.put("property", null); out.put("unit", null); return out; }
        Map<String, Object> l = leases.get(0);
        out.put("property", subject("PROPERTY", (UUID) l.get("property_id"), String.valueOf(l.get("property_name")), ctx));
        out.put("unit", subject("UNIT", (UUID) l.get("unit_id"), String.valueOf(l.get("unit_label")), ctx));
        return out;
    }

    private Map<String, Object> subject(String type, UUID id, String name, UserContext ctx) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("id", id);
        m.put("name", name);
        List<Map<String, Object>> mine = db.list("select stars, comment, updated_at from leasing.review where subject_type = ? and subject_id = ? and rater_user_id = ?", type, id, ctx.userId());
        m.put("review", mine.isEmpty() ? null : mine.get(0));
        return m;
    }

    /** Adds or changes the resident's rating. Body: subjectType (PROPERTY or UNIT), subjectId, stars 1 to 5, optional comment. */
    @Transactional
    public Map<String, Object> rate(Map<String, Object> body) {
        UserContext ctx = resident();
        String type = Optional.ofNullable(Req.str(body, "subjectType")).orElse("").toUpperCase();
        if (!List.of("PROPERTY", "UNIT").contains(type)) throw ApiException.badRequest("Say whether you are rating the property or the unit.");
        UUID subject = Req.requiredUuid(body, "subjectId");
        Integer stars = Req.integer(body, "stars");
        if (stars == null || stars < 1 || stars > 5) throw ApiException.badRequest("Choose between 1 and 5 stars.");
        String comment = Req.str(body, "comment");
        if (comment != null && comment.length() > 1000) throw ApiException.badRequest("Keep the comment under 1,000 characters.");
        List<Map<String, Object>> lease = db.list("""
                select property_id, unit_id, owner_id from leasing.lease where tenant_id = ? and status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN','ENDED')
                and (property_id = ? or unit_id = ?) limit 1""", ctx.partyId(), subject, subject);
        boolean ok = !lease.isEmpty() && (("PROPERTY".equals(type) && subject.equals(lease.get(0).get("property_id"))) || ("UNIT".equals(type) && subject.equals(lease.get(0).get("unit_id"))));
        if (!ok) throw ApiException.forbidden("You can rate a home you have a lease on.");
        Map<String, Object> l = lease.get(0);
        String text = comment == null || comment.isBlank() ? null : comment.trim();
        // The comment is scored before it is saved. A score under the minimum keeps the comment off the public page; the stars still count.
        ReviewModeration.Result mod = moderation.score(text);
        db.update("""
                insert into leasing.review (id, org_id, subject_type, subject_id, property_id, unit_id, owner_id, tenant_id, rater_user_id, rater_name, stars, comment, ai_score, ai_source, ai_reasons)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (subject_type, subject_id, rater_user_id) do update set stars = excluded.stars, comment = excluded.comment, updated_at = now(),
                    ai_score = excluded.ai_score, ai_source = excluded.ai_source, ai_reasons = excluded.ai_reasons,
                    -- new words need a new decision: a staff decision about the old comment does not carry over
                    moderation_override = case when excluded.comment is not distinct from review.comment then review.moderation_override end,
                    override_by = case when excluded.comment is not distinct from review.comment then review.override_by end,
                    override_at = case when excluded.comment is not distinct from review.comment then review.override_at end,
                    override_reason = case when excluded.comment is not distinct from review.comment then review.override_reason end""",
                UUID.randomUUID(), ctx.orgId(), type, subject, l.get("property_id"), l.get("unit_id"), l.get("owner_id"), ctx.partyId(), ctx.userId(), ctx.name(), stars,
                text, mod == null ? null : mod.score(), mod == null ? null : mod.source(), mod == null ? null : String.join(" ", mod.reasons()));
        audit.record(type.equals("UNIT") ? "Unit" : "Property", subject, "RATED", null, null, Map.of("stars", stars, "by", ctx.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("saved", true);
        out.put("stars", stars);
        boolean shown = mod == null || ReviewModeration.visible(mod.score(), moderation.minScore(ctx.orgId()));
        out.put("comment_public", shown);
        if (!shown) out.put("comment_note", "Your rating counts, but your comment was not published because it may break our community rules. Edit it to be about the home and resubmit.");
        return out;
    }

    /**
     * Staff: writes, changes or (with empty text) removes the company's public reply to a review. The reply shows under the comment on the public
     * listing page. Row-level security limits this to reviews of the company's own properties, so another company's review is not found.
     *
     * @throws ApiException (400) when the reply is over 1,000 characters, (403) for a resident, owner or vendor, (404) when the review is not the company's
     */
    @Transactional
    public Map<String, Object> reply(UUID reviewId, String text) {
        UserContext ctx = UserContextHolder.require();
        if (UserContext.SCOPE_TENANT.equals(ctx.scope()) || UserContext.SCOPE_OWNER.equals(ctx.scope()) || UserContext.SCOPE_VENDOR.equals(ctx.scope()))
            throw ApiException.forbidden("Only the property management team can reply to a review.");
        requireReviewTools();
        String t = text == null ? "" : text.trim();
        if (t.length() > 1000) throw ApiException.badRequest("Keep the reply under 1,000 characters.");
        int n = t.isEmpty()
                ? db.update("update leasing.review set reply = null, reply_by = null, replied_at = null where id = ?", reviewId)
                : db.update("update leasing.review set reply = ?, reply_by = ?, replied_at = now() where id = ?", t, ctx.name(), reviewId);
        if (n == 0) throw ApiException.notFound("Review");
        audit.record("Review", reviewId, t.isEmpty() ? "REVIEW_REPLY_REMOVED" : "REVIEW_REPLIED", null, null, Map.of("by", ctx.name()));
        return Map.of("saved", true);
    }

    /**
     * Staff: overrides the AI's decision about one comment. SHOW publishes it whatever its score, HIDE keeps it off the public page, CLEAR hands the
     * decision back to the AI score. A reason is required for SHOW and HIDE. The resident's star rating is never changed. Row-level security limits this
     * to reviews of the company's own properties, and the decision is audited with who made it.
     *
     * @throws ApiException (400) for an unknown decision or a missing or over-long reason, (403) for a resident, owner or vendor, (404) when the review is not the company's
     */
    @Transactional
    public Map<String, Object> moderate(UUID reviewId, String decision, String reason) {
        UserContext ctx = UserContextHolder.require();
        if (UserContext.SCOPE_TENANT.equals(ctx.scope()) || UserContext.SCOPE_OWNER.equals(ctx.scope()) || UserContext.SCOPE_VENDOR.equals(ctx.scope()))
            throw ApiException.forbidden("Only the property management team can change what is shown.");
        requireReviewTools();
        String d = decision == null ? "" : decision.trim().toUpperCase();
        if (!List.of("SHOW", "HIDE", "CLEAR").contains(d)) throw ApiException.badRequest("Choose show, hide or back to the AI decision.");
        String why = reason == null ? "" : reason.trim();
        if (!d.equals("CLEAR") && why.isEmpty()) throw ApiException.badRequest("Give a short reason. It is kept in the audit trail.");
        if (why.length() > 300) throw ApiException.badRequest("Keep the reason under 300 characters.");
        int n = d.equals("CLEAR")
                ? db.update("update leasing.review set moderation_override = null, override_by = null, override_at = null, override_reason = null where id = ?", reviewId)
                : db.update("update leasing.review set moderation_override = ?, override_by = ?, override_at = now(), override_reason = ? where id = ?", d, ctx.name(), why, reviewId);
        if (n == 0) throw ApiException.notFound("Review");
        audit.record("Review", reviewId, "REVIEW_MODERATION_" + d, null, null, Map.of("by", ctx.name(), "reason", why));
        return Map.of("saved", true, "decision", d);
    }

    /** Takes back the resident's own rating. */
    @Transactional
    public void withdraw(String type, UUID subject) {
        UserContext ctx = resident();
        db.update("delete from leasing.review where subject_type = ? and subject_id = ? and rater_user_id = ?", type.toUpperCase(), subject, ctx.userId());
    }

    /**
     * The ratings of one property or unit that the signed-in person may see: the average, the count, how the stars are spread, and
     * the written comments (staff and owners see everyone's, a resident only their own).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reviews(String type, UUID subject) {
        String t = type.toUpperCase();
        if (!List.of("PROPERTY", "UNIT").contains(t)) throw ApiException.badRequest("Say whether you want the ratings of a property or a unit.");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> s = db.list("select count(*) as count, coalesce(round(avg(stars), 2), 0) as average from leasing.review where subject_type = ? and subject_id = ?", t, subject).get(0);
        out.put("count", s.get("count"));
        out.put("average", s.get("average"));
        Map<Integer, Long> dist = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 1; i <= 5; i++) dist.put(i, 0L);
        for (Map<String, Object> r : db.list("select stars, count(*) as n from leasing.review where subject_type = ? and subject_id = ? group by stars", t, subject))
            dist.put(((Number) r.get("stars")).intValue(), ((Number) r.get("n")).longValue());
        out.put("distribution", dist);
        int min = moderation.minScore(UserContextHolder.orgId());
        List<Map<String, Object>> rows = db.page("select id, rater_name, stars, comment, created_at, reply, reply_by, replied_at, ai_score, ai_source, ai_reasons, moderation_override, override_by, override_at, override_reason from leasing.review where subject_type = ? and subject_id = ? order by created_at desc", t, subject);
        // Staff see every comment, and which ones visitors cannot see: a comment with a score under the minimum, or not scored yet.
        for (Map<String, Object> r : rows) {
            boolean written = r.get("comment") != null && !String.valueOf(r.get("comment")).isBlank();
            Integer score = r.get("ai_score") == null ? null : ((Number) r.get("ai_score")).intValue();
            r.put("hidden_from_public", written && !ReviewModeration.visible(score, (String) r.get("moderation_override"), min));
        }
        out.put("min_score", min);
        out.put("rows", rows);
        return out;
    }
}
