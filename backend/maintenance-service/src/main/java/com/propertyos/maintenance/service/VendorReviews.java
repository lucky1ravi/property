package com.propertyos.maintenance.service;

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
 * Ratings and comments about vendors, by the people who dealt with them:
 * <ul>
 *   <li>company staff can rate any vendor;</li>
 *   <li>an owner can rate a vendor who has worked on one of their properties;</li>
 *   <li>a resident can rate the vendor who did one of their own repairs, once it is done.</li>
 * </ul>
 * Each person has one rating per job (or one general rating). What anyone can read follows the usual row-level security: staff see
 * everything, a vendor sees what is said about them, owners and residents see only what they wrote.
 */
@Service
public class VendorReviews {
    private final Db db;
    private final AuditService audit;

    public VendorReviews(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    private static String roleOf(UserContext ctx) {
        return switch (ctx.scope()) {
            case UserContext.SCOPE_OWNER -> "OWNER";
            case UserContext.SCOPE_TENANT -> "RESIDENT";
            default -> "STAFF";
        };
    }

    /** Adds or changes the signed-in person's rating of a vendor. */
    @Transactional
    public Map<String, Object> rate(UUID vendorId, Map<String, Object> body) {
        UserContext ctx = UserContextHolder.require();
        Integer stars = Req.integer(body, "stars");
        if (stars == null || stars < 1 || stars > 5) throw ApiException.badRequest("Choose between 1 and 5 stars.");
        String comment = Req.str(body, "comment");
        if (comment != null && comment.length() > 1000) throw ApiException.badRequest("Keep the comment under 1,000 characters.");
        UUID workOrder = body.get("workOrderId") == null ? null : Req.requiredUuid(body, "workOrderId");
        String scope = ctx.scope();
        if (UserContext.SCOPE_VENDOR.equals(scope) || UserContext.SCOPE_PLATFORM.equals(scope)) throw ApiException.forbidden("Your role can't rate vendors.");
        // Residents and owners cannot read the vendor list, so they are checked through the repair they name instead.
        if (!UserContext.SCOPE_TENANT.equals(scope) && !UserContext.SCOPE_OWNER.equals(scope)) db.one("Vendor", "select id from maintenance.vendor where id = ?", vendorId);
        UUID property = null, owner = null, tenant = null;
        if (workOrder != null) {
            Map<String, Object> wo = db.one("Work order", "select id, vendor_id, property_id, owner_id, tenant_id, status from maintenance.work_order where id = ?", workOrder);
            if (!vendorId.equals(wo.get("vendor_id"))) throw ApiException.badRequest("That repair was not done by this vendor.");
            property = (UUID) wo.get("property_id");
            owner = (UUID) wo.get("owner_id");
            tenant = (UUID) wo.get("tenant_id");
            if (UserContext.SCOPE_TENANT.equals(scope) && !List.of("COMPLETED", "CLOSED").contains(wo.get("status")))
                throw ApiException.conflict("You can rate the work once the vendor has finished it.");
        } else if (UserContext.SCOPE_TENANT.equals(scope)) {
            throw ApiException.badRequest("Choose which repair you are rating.");
        } else if (UserContext.SCOPE_OWNER.equals(scope)
                && db.value(Long.class, "select count(*) from maintenance.work_order where vendor_id = ? and owner_id = ?", vendorId, ctx.partyId()) == 0) {
            throw ApiException.forbidden("You can rate vendors who have worked on your properties.");
        }
        // A resident's and an owner's rating is filed under their own party so row-level security shows it back to them.
        // Only the rater's own party is filed on the review, so an owner or resident never reads what staff wrote about the same job.
        tenant = UserContext.SCOPE_TENANT.equals(scope) ? ctx.partyId() : null;
        owner = UserContext.SCOPE_OWNER.equals(scope) ? ctx.partyId() : null;
        UUID id = UUID.randomUUID();
        int changed = db.update("""
                insert into maintenance.vendor_review (id, org_id, vendor_id, work_order_id, property_id, owner_id, tenant_id, rater_user_id, rater_name, rater_role, stars, comment)
                values (?,?,?,?,?,?,?,?,?,?,?,?)
                on conflict (vendor_id, rater_user_id, coalesce(work_order_id, '00000000-0000-0000-0000-000000000000'::uuid))
                do update set stars = excluded.stars, comment = excluded.comment, updated_at = now()""",
                id, ctx.orgId(), vendorId, workOrder, property, owner, tenant, ctx.userId(), ctx.name(), roleOf(ctx), stars, comment == null || comment.isBlank() ? null : comment.trim());
        audit.record("Vendor", vendorId, "VENDOR_RATED", null, null, Map.of("stars", stars, "by", ctx.name(), "role", roleOf(ctx)));
        return Map.of("saved", changed > 0, "stars", stars);
    }

    /**
     * The ratings the signed-in person is allowed to see, with the average, the count and how many gave each number of stars.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> reviews(UUID vendorId) {
        UserContext who = UserContextHolder.require();
        if (!UserContext.SCOPE_TENANT.equals(who.scope()) && !UserContext.SCOPE_OWNER.equals(who.scope())) db.one("Vendor", "select id from maintenance.vendor where id = ?", vendorId);
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> s = db.list("select count(*) as count, coalesce(round(avg(stars), 2), 0) as average from maintenance.vendor_review where vendor_id = ?", vendorId).get(0);
        out.put("count", s.get("count"));
        out.put("average", s.get("average"));
        Map<Integer, Long> dist = new TreeMap<>(Comparator.reverseOrder());
        for (int i = 1; i <= 5; i++) dist.put(i, 0L);
        for (Map<String, Object> r : db.list("select stars, count(*) as n from maintenance.vendor_review where vendor_id = ? group by stars", vendorId)) dist.put(((Number) r.get("stars")).intValue(), ((Number) r.get("n")).longValue());
        out.put("distribution", dist);
        UserContext ctx = UserContextHolder.require();
        out.put("canRate", !(UserContext.SCOPE_VENDOR.equals(ctx.scope()) || UserContext.SCOPE_PLATFORM.equals(ctx.scope())));
        out.put("rows", db.page("select id, work_order_id, rater_name, rater_role, stars, comment, created_at from maintenance.vendor_review where vendor_id = ? order by created_at desc", vendorId));
        return out;
    }
}
