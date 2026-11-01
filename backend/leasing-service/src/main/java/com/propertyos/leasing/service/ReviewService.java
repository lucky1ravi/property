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

    public ReviewService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
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
        db.update("""
                insert into leasing.review (id, org_id, subject_type, subject_id, property_id, unit_id, owner_id, tenant_id, rater_user_id, rater_name, stars, comment)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (subject_type, subject_id, rater_user_id) do update set stars = excluded.stars, comment = excluded.comment, updated_at = now()""",
                UUID.randomUUID(), ctx.orgId(), type, subject, l.get("property_id"), l.get("unit_id"), l.get("owner_id"), ctx.partyId(), ctx.userId(), ctx.name(), stars,
                comment == null || comment.isBlank() ? null : comment.trim());
        audit.record(type.equals("UNIT") ? "Unit" : "Property", subject, "RATED", null, null, Map.of("stars", stars, "by", ctx.name()));
        return Map.of("saved", true, "stars", stars);
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
        out.put("rows", db.page("select id, rater_name, stars, comment, created_at from leasing.review where subject_type = ? and subject_id = ? order by created_at desc", t, subject));
        return out;
    }
}
