package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Explains a listing's place in public search ("Why is this ranked here?"). It runs as the signed-in person, so row-level security limits it to
 * their own company's listings: another company's listing is simply not found, and nothing about other companies' homes is read or returned.
 */
@Service
public class RankingService {
    private final Db db;
    private final ConfigClient config;

    private final PublicCatalog catalog;

    public RankingService(Db db, ConfigClient config, PublicCatalog catalog) {
        this.db = db;
        this.config = config;
        this.catalog = catalog;
    }

    /**
     * Every listing of the signed-in person's company with its score, where it sits on the public site and what would help most. Row-level
     * security limits the listings to the company's own; the position is a number out of the homes shown, and says nothing about any other home.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        UUID org = UserContextHolder.orgId();
        JsonNode cfg = config.forOrg(org);
        ListingRanking.Settings s = ListingRanking.settings(cfg.has("ranking") ? cfg.get("ranking") : config.defaults().path("ranking"));
        Map<UUID, Double> tiers = Map.of(org, ListingRanking.tier(cfg.path("plans"), cfg.path("plan").asText("")));
        List<Map<String, Object>> rows = db.list("""
                select l.id, l.title, l.status, p.name as property_name, u.number as unit_number, %s as score, f.f_rating, f.f_fresh, f.f_quality, f.boosted
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                %s order by score desc, l.title limit 500""".formatted(ListingRanking.scoreSql(s), ListingRanking.joins(s, tiers)));
        PublicCatalog.Positions pos = catalog.positions(org);
        ListingRanking.Boost boost = ListingRanking.Boost.of(cfg.has("boost") ? cfg.get("boost") : config.defaults().path("boost"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("id", "title", "status", "property_name", "unit_number", "score")) m.put(k, r.get(k));
            boolean sponsored = ((Number) r.get("boosted")).intValue() == 1;
            boolean applied = pos.lifted().contains((UUID) r.get("id"));
            m.put("sponsored", sponsored);
            m.put("bonus_applied", applied);
            // The score includes the bonus only while the page cap lets this home carry it.
            if (applied) m.put("score", Math.round((((Number) r.get("score")).doubleValue() + boost.bonusPoints()) * 10.0) / 10.0);
            m.put("position", "LIVE".equals(r.get("status")) ? pos.byListing().get((UUID) r.get("id")) : null);
            m.put("improve", ListingRanking.weakest(s, r));
            out.add(m);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("total_homes", pos.total());
        res.put("rows", out);
        return res;
    }

    /**
     * The score of one listing with one line per factor.
     *
     * @return listing id, status, whether it is shown in public search, the score out of 100 and the factors
     * @throws com.propertyos.common.web.ApiException (404) when the listing is not in the caller's company
     */
    @Transactional(readOnly = true)
    public Map<String, Object> explain(UUID listingId) {
        UUID org = UserContextHolder.orgId();
        JsonNode cfg = config.forOrg(org);
        ListingRanking.Settings s = ListingRanking.settings(cfg.has("ranking") ? cfg.get("ranking") : config.defaults().path("ranking"));
        String plan = cfg.path("plan").asText("");
        Map<UUID, Double> tiers = Map.of(org, ListingRanking.tier(cfg.path("plans"), plan));
        Map<String, Object> row = db.one("Listing", """
                select l.status, %s as score, f.f_plan, f.f_rating, f.f_fresh, f.f_quality, f.photos, f.desc_len, f.fields, f.age_days,
                       rp.n as p_n, rp.s as p_s, ru.n as u_n, ru.s as u_s, f.boosted,
                       (select max(bo.ends_at) from leasing.listing_boost bo where bo.listing_id = l.id and bo.status = 'ACTIVE' and bo.ends_at > now()) as boost_ends
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                %s where l.id = ?""".formatted(ListingRanking.scoreSql(s), ListingRanking.joins(s, tiers)), listingId);
        ListingRanking.Boost boost = ListingRanking.Boost.of(cfg.has("boost") ? cfg.get("boost") : config.defaults().path("boost"));
        List<Map<String, Object>> factors = ListingRanking.explain(s, row, plan);
        double score = ((Number) row.get("score")).doubleValue();
        boolean sponsored = ((Number) row.get("boosted")).intValue() == 1;
        if (sponsored) {
            boolean applied = catalog.positions(org).lifted().contains(listingId);
            String ends = String.valueOf(row.get("boost_ends"));
            factors.add(ListingRanking.boostLine(boost, ends.length() >= 10 ? ends.substring(0, 10) : "the end of its period", applied));
            if (applied) score += boost.bonusPoints();
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("listing_id", listingId);
        out.put("status", row.get("status"));
        out.put("in_search", "LIVE".equals(row.get("status")));
        out.put("score", Math.round(score * 10.0) / 10.0);
        out.put("max", sponsored ? 100 + (int) boost.bonusPoints() : 100);
        out.put("factors", factors);
        return out;
    }
}
