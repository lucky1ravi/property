package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;

/**
 * How a live listing is ranked in public search ("Recommended"). The score is 0 to 100: a weighted average of four factors that are each 0 to 1.
 *
 * <ul>
 *   <li><b>plan</b>: the owning company's plan tier, its price per unit scaled between the cheapest and dearest plan.</li>
 *   <li><b>rating</b>: resident ratings of the property and the unit, each smoothed with a Bayesian average so a single review cannot dominate.</li>
 *   <li><b>freshness</b>: halves every {@code halfLifeDays} since the listing was published.</li>
 *   <li><b>quality</b>: photos, description length and how many key fields are filled in.</li>
 * </ul>
 *
 * The weights and the smoothing prior are the {@code ranking} section of the product configuration, so the PropertyOS operator can change them.
 * This class only builds the SQL and the explanation; the database computes the score so search can sort and page on it.
 * Every number placed in SQL is a validated, clamped double, never text from a request.
 */
final class ListingRanking {
    private ListingRanking() {
    }

    /** The configured weights and smoothing. Missing or nonsense values fall back to the defaults. */
    record Settings(double wPlan, double wRating, double wFresh, double wQuality,
                    double priorMean, double priorWeight, double unitShare,
                    double halfLifeDays, int photosTarget, int descriptionChars) {
        double totalWeight() {
            return wPlan + wRating + wFresh + wQuality;
        }
    }

    static Settings settings(JsonNode r) {
        JsonNode w = r == null ? null : r.path("weights");
        double wp = num(w, "plan", 15, 0, 1000), wr = num(w, "rating", 35, 0, 1000), wf = num(w, "freshness", 20, 0, 1000), wq = num(w, "quality", 30, 0, 1000);
        if (wp + wr + wf + wq <= 0) { wp = 15; wr = 35; wf = 20; wq = 30; }   // all-zero weights would divide by zero: use the defaults
        JsonNode rt = r == null ? null : r.path("rating");
        JsonNode fr = r == null ? null : r.path("freshness");
        JsonNode ql = r == null ? null : r.path("quality");
        return new Settings(wp, wr, wf, wq,
                num(rt, "priorMean", 4.0, 1, 5), num(rt, "priorWeight", 5, 0, 1000), num(rt, "unitShare", 0.4, 0, 1),
                num(fr, "halfLifeDays", 30, 1, 3650), (int) num(ql, "photosTarget", 8, 1, 50), (int) num(ql, "descriptionChars", 300, 1, 5000));
    }

    private static double num(JsonNode parent, String key, double fallback, double min, double max) {
        JsonNode n = parent == null ? null : parent.path(key);
        if (n == null || !n.isNumber()) return fallback;
        double v = n.asDouble();
        if (Double.isNaN(v) || Double.isInfinite(v)) return fallback;
        return Math.max(min, Math.min(max, v));
    }

    /** A plan's tier from 0 (the cheapest plan) to 1 (the dearest), from the plans' price per unit. 0 when the plan is unknown or all plans cost the same. */
    static double tier(JsonNode plans, String plan) {
        if (plans == null || plan == null || !plans.isObject() || !plans.has(plan)) return 0;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (JsonNode p : plans) {
            double price = p.path("pricePerUnit").asDouble(0);
            lo = Math.min(lo, price);
            hi = Math.max(hi, price);
        }
        if (hi <= lo) return 0;
        return Math.max(0, Math.min(1, (plans.path(plan).path("pricePerUnit").asDouble(0) - lo) / (hi - lo)));
    }

    private static String n(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    /**
     * The extra FROM items for a query that already joins {@code leasing.listing l}, {@code leasing.property p} and {@code leasing.unit u}.
     * They add {@code rp} and {@code ru} (review count and star total for the property and the unit) and {@code f}, the raw inputs and the four 0 to 1 factors.
     *
     * @param tiers plan tier by company id; a company not listed counts as tier 0
     */
    static String joins(Settings s, Map<UUID, Double> tiers) {
        StringBuilder plan = new StringBuilder("case l.org_id::text");
        tiers.forEach((org, t) -> plan.append(" when '").append(org).append("' then ").append(n(t)));
        plan.append(" else 0 end");
        String smooth = "(%s * %s + coalesce(%%s.s, 0)) / (%s + coalesce(%%s.n, 0))".formatted(n(s.priorWeight()), n(s.priorMean()), n(s.priorWeight()));
        String prop = smooth.formatted("rp", "rp"), unit = smooth.formatted("ru", "ru");
        return """
                left join lateral (select count(*)::numeric as n, coalesce(sum(r.stars), 0)::numeric as s from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) rp on true
                left join lateral (select count(*)::numeric as n, coalesce(sum(r.stars), 0)::numeric as s from leasing.review r where r.subject_type = 'UNIT' and r.subject_id = u.id) ru on true
                cross join lateral (select
                    jsonb_array_length(l.photos) as photos,
                    length(coalesce(l.description, '')) as desc_len,
                    ((case when l.available_date is not null then 1 else 0 end) + (case when l.deposit > 0 then 1 else 0 end)
                     + (case when jsonb_array_length(l.highlights) > 0 then 1 else 0 end) + (case when u.sqft > 0 then 1 else 0 end)
                     + (case when jsonb_array_length(p.amenities) > 0 then 1 else 0 end) + (case when p.year_built is not null then 1 else 0 end)) as fields,
                    greatest(0, extract(epoch from (now() - coalesce(l.published_at, l.created_at))) / 86400.0) as age_days,
                    (case when exists (select 1 from leasing.listing_boost b where b.listing_id = l.id and b.status = 'ACTIVE' and b.starts_at <= now() and b.ends_at > now())
                          then 1 else 0 end) as boosted,
                    %s::numeric as f_plan,
                    (%s * (((%s) - 1) / 4.0) + (1 - %s) * (((%s) - 1) / 4.0)) as f_rating,
                    power(0.5, greatest(0, extract(epoch from (now() - coalesce(l.published_at, l.created_at))) / 86400.0) / %s) as f_fresh,
                    ((least(jsonb_array_length(l.photos), %d) / %d.0) + (least(length(coalesce(l.description, '')), %d) / %d.0)
                     + (((case when l.available_date is not null then 1 else 0 end) + (case when l.deposit > 0 then 1 else 0 end)
                     + (case when jsonb_array_length(l.highlights) > 0 then 1 else 0 end) + (case when u.sqft > 0 then 1 else 0 end)
                     + (case when jsonb_array_length(p.amenities) > 0 then 1 else 0 end) + (case when p.year_built is not null then 1 else 0 end)) / 6.0)) / 3.0 as f_quality
                ) f
                """.formatted(plan, n(s.unitShare()), unit, n(s.unitShare()), prop, n(s.halfLifeDays()),
                s.photosTarget(), s.photosTarget(), s.descriptionChars(), s.descriptionChars());
    }

    /** The score expression, 0 to 100, for a query that uses {@link #joins}. */
    static String scoreSql(Settings s) {
        return "round(cast(100 * (%s * f.f_plan + %s * f.f_rating + %s * f.f_fresh + %s * f.f_quality) / %s as numeric), 1)"
                .formatted(n(s.wPlan()), n(s.wRating()), n(s.wFresh()), n(s.wQuality()), n(s.totalWeight()));
    }

    /**
     * What a boost is worth and how many boosted homes it may lift. Prices are the operator's {@code boost.prices} (days to dollars); the bonus is
     * added to the score of a boosted home, but only for as many boosted homes as fit within {@code maxSharePercent} of a page of results, so paid
     * homes cannot fill the page.
     */
    record Boost(Map<Integer, java.math.BigDecimal> prices, double bonusPoints, int maxSharePercent, int unpaidExpiryDays,
                 boolean enabled, Map<String, Integer> planDiscountPercent) {
        /** The percentage off for companies on this plan, 0 when none is set. */
        int discountFor(String plan) {
            return plan == null ? 0 : planDiscountPercent.getOrDefault(plan, 0);
        }

        static Boost of(JsonNode b) {
            Map<Integer, java.math.BigDecimal> prices = new TreeMap<>();
            JsonNode p = b == null ? null : b.path("prices");
            if (p != null && p.isObject()) {
                p.fields().forEachRemaining(e -> {
                    try {
                        int days = Integer.parseInt(e.getKey().trim());
                        java.math.BigDecimal price = new java.math.BigDecimal(e.getValue().asText()).setScale(2, java.math.RoundingMode.HALF_UP);
                        if (days > 0 && days <= 365 && price.signum() > 0) prices.put(days, price);
                    } catch (NumberFormatException ignored) {
                        // a price the operator mistyped is skipped rather than breaking the page
                    }
                });
            }
            Map<String, Integer> discounts = new HashMap<>();
            JsonNode d = b == null ? null : b.path("planDiscountPercent");
            if (d != null && d.isObject()) d.fields().forEachRemaining(e -> { if (e.getValue().isNumber()) discounts.put(e.getKey(), (int) Math.max(0, Math.min(100, e.getValue().asInt()))); });
            boolean enabled = b == null || !b.path("onSale").isBoolean() || b.path("onSale").asBoolean();
            return new Boost(prices, num(b, "bonusPoints", 15, 0, 100), (int) num(b, "maxSharePercent", 30, 0, 100), (int) num(b, "unpaidExpiryDays", 14, 1, 365), enabled, discounts);
        }

        /** How many boosted homes on one page of {@code pageSize} may carry the bonus: the share of the page, at least one unless the share is 0. */
        int capPerPage(int pageSize) {
            if (maxSharePercent <= 0 || bonusPoints <= 0) return 0;
            return Math.max(1, pageSize * maxSharePercent / 100);
        }
    }

    /**
     * The window that numbers the boosted homes from best to worst by their own score (use inside the select that has {@link #joins}); the bonus
     * goes only to those numbered within the page cap.
     */
    static String boostRankSql(Settings s) {
        return "row_number() over (partition by f.boosted order by " + scoreSql(s) + " desc, l.published_at desc nulls last)";
    }

    /** The final score from {@code base_score}, {@code boosted} and {@code brank} columns (of the table alias {@code t}), with the boost bonus added to the boosted homes within the cap. */
    static String finalScoreSql(Boost b, int cap, String t) {
        return "(case when %sboosted = 1 and %sbrank <= %d then %sbase_score + %s else %sbase_score end)".formatted(t, t, cap, t, n(b.bonusPoints()), t);
    }

    /** The smoothed star rating (1 to 5) used by the "Top rated" sort, for a query that uses {@link #joins}. A home with no reviews counts as the prior mean. */
    static String ratingRankSql() {
        return "round(cast(f.f_rating * 4 + 1 as numeric), 2)";
    }

    /**
     * The factor, other than the plan, where the listing is furthest below its best: the most useful thing for staff to improve.
     * Null when nothing is below 90 percent of its best.
     */
    static String weakest(Settings s, Map<String, Object> row) {
        String[] label = {"Resident ratings", "Freshness", "Listing quality"};
        double[] gap = {s.wRating() * (1 - num(row.get("f_rating"))), s.wFresh() * (1 - num(row.get("f_fresh"))), s.wQuality() * (1 - num(row.get("f_quality")))};
        double[] best = {s.wRating(), s.wFresh(), s.wQuality()};
        int worst = -1;
        for (int i = 0; i < gap.length; i++) if (gap[i] > 0.1 * best[i] && (worst < 0 || gap[i] > gap[worst])) worst = i;
        return worst < 0 ? null : label[worst];
    }

    private static double num(Object o) {
        return o instanceof Number x ? x.doubleValue() : 0;
    }

    private static double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }

    private static String stars(double count, double total) {
        return count <= 0 ? "no reviews yet" : "%.1f from %d review%s".formatted(total / count, (long) count, count == 1 ? "" : "s");
    }

    /**
     * One line per factor, in plain words, with the points it adds and the most it could add.
     *
     * @param row   the listing's raw inputs and factors: {@code f_plan f_rating f_fresh f_quality photos desc_len fields age_days p_n p_s u_n u_s}
     * @param plan  the owning company's plan name, for the plan line
     */
    static List<Map<String, Object>> explain(Settings s, Map<String, Object> row, String plan) {
        double total = s.totalWeight();
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(line("plan", "Plan tier", s.wPlan(), total, num(row.get("f_plan")),
                "%s plan, tier %.2f of 1 (set by the plan's price per unit).".formatted(plan == null || plan.isBlank() ? "Your" : plan, num(row.get("f_plan")))));
        out.add(line("rating", "Resident ratings", s.wRating(), total, num(row.get("f_rating")),
                "Property: %s. Unit: %s. Smoothed toward %.1f stars as if %s other reviews said so, so one review cannot dominate."
                        .formatted(stars(num(row.get("p_n")), num(row.get("p_s"))), stars(num(row.get("u_n")), num(row.get("u_s"))), s.priorMean(), trim(s.priorWeight()))));
        out.add(line("freshness", "Freshness", s.wFresh(), total, num(row.get("f_fresh")),
                "Published %d day%s ago; the value halves every %s days.".formatted((long) num(row.get("age_days")), (long) num(row.get("age_days")) == 1 ? "" : "s", trim(s.halfLifeDays()))));
        out.add(line("quality", "Listing quality", s.wQuality(), total, num(row.get("f_quality")),
                "%d of %d photos, a %d of %d character description, %d of 6 key fields filled in."
                        .formatted((long) num(row.get("photos")), s.photosTarget(), (long) num(row.get("desc_len")), s.descriptionChars(), (long) num(row.get("fields")))));
        return out;
    }

    /** The explanation line for an active boost. It adds up to the bonus: only boosted homes within the page cap receive it. */
    static Map<String, Object> boostLine(Boost b, String until, boolean applied) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", "boost");
        m.put("label", "Boost (sponsored)");
        m.put("points", applied ? round1(b.bonusPoints()) : 0.0);
        m.put("max", round1(b.bonusPoints()));
        m.put("detail", applied
                ? "Boosted until %s. Adds %s points. At most %d%% of a page of results can carry the bonus, so paid homes never fill a page.".formatted(until, trim(b.bonusPoints()), b.maxSharePercent())
                : "Boosted until %s, and still labelled Sponsored, but the bonus is not applied right now: other boosted homes already take the %d%% of a page that boosts may lift."
                        .formatted(until, b.maxSharePercent()));
        return m;
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.ROOT, "%.1f", v);
    }

    private static Map<String, Object> line(String key, String label, double weight, double total, double factor, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("label", label);
        m.put("points", round1(100 * weight / total * factor));
        m.put("max", round1(100 * weight / total));
        m.put("detail", detail);
        return m;
    }

    /** "Rae Resident" becomes "Rae R." so public reviews do not publish a full name. */
    static String publicName(String name) {
        if (name == null || name.isBlank()) return "A resident";
        String[] p = name.trim().split("\\s+");
        return p.length == 1 ? p[0] : p[0] + " " + Character.toUpperCase(p[p.length - 1].charAt(0)) + ".";
    }
}
