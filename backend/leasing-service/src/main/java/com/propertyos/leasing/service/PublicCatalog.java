package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.SuspendedOrgs;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * What a visitor can browse on the public homes site: homes one by one or grouped by property with the units each has available,
 * filtered and sorted and paged, the choices to offer in the filters, and who is renting the home out. Visitors are not signed in, so
 * everything runs as the system with explicit filters: only published ("live") listings of companies that are not suspended.
 */
@Service
public class PublicCatalog {
    private final Db db;
    private final ConfigClient config;
    private final TransactionTemplate tx;
    private SuspendedOrgs suspended;

    public PublicCatalog(Db db, ConfigClient config, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.tx = new TransactionTemplate(tm);
    }

    @Autowired(required = false)
    void setSuspended(SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    private <T> T asSystem(Supplier<T> work) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(st -> work.get()));
    }

    // ----------------------------------------------------------------------------------------------- ranking

    /** The ranking settings in force and each company's plan tier, for the companies that have live homes. */
    private record Ranking(ListingRanking.Settings settings, Map<UUID, Double> tiers, ListingRanking.Boost boost) {
    }

    /** Homes per page on the public site (its search page asks for 12). Used when a request is not paged, such as the staff ranking view, so the boost cap means the same thing there. */
    private static final int DEFAULT_PAGE = 12;

    /** How many boosted homes may carry the bonus on one page of this request. */
    private static int boostCap(ListingRanking.Boost b) {
        return b.capPerPage(com.propertyos.common.web.PageParams.current().map(q -> q.size()).orElse(DEFAULT_PAGE));
    }

    private static final long RANKING_TTL_MS = 15_000;
    private volatile Ranking cachedRanking;
    private volatile long cachedRankingAt;

    /**
     * The "ranking" settings are the PropertyOS operator's, the same for every company, so they are read from the first company's configuration;
     * each company's own plan gives its tier. This page lists the homes of every company, so it must stay cheap as companies are added: the result
     * is kept for 15 seconds (as long as configuration itself is cached), and the company configuration calls happen outside any database transaction.
     */
    private Ranking ranking() {
        Ranking c = cachedRanking;
        if (c != null && System.currentTimeMillis() - cachedRankingAt < RANKING_TTL_MS) return c;
        List<UUID> orgs = asSystem(() -> db.list("select distinct l.org_id from leasing.listing l where l.status = 'LIVE'")).stream().map(r -> (UUID) r.get("org_id")).toList();
        Map<UUID, Double> tiers = new HashMap<>();
        JsonNode ranking = config.defaults().path("ranking"), boost = config.defaults().path("boost");
        for (int i = 0; i < orgs.size(); i++) {
            JsonNode cfg = config.forOrg(orgs.get(i));
            if (i == 0 && cfg.has("ranking")) ranking = cfg.get("ranking");
            if (i == 0 && cfg.has("boost")) boost = cfg.get("boost");
            tiers.put(orgs.get(i), ListingRanking.tier(cfg.path("plans"), cfg.path("plan").asText("")));
        }
        Ranking r = new Ranking(ListingRanking.settings(ranking), tiers, ListingRanking.Boost.of(boost));
        cachedRanking = r;
        cachedRankingAt = System.currentTimeMillis();
        return r;
    }

    /** Where a company's live homes sit in public search, out of how many homes are shown. */
    record Positions(Map<UUID, Integer> byListing, Set<UUID> lifted, int total) {
    }

    /**
     * The place of each of one company's live homes in the "Recommended" order of the whole public site, and how many homes the site shows.
     * Only the company's own homes are returned, with a position and the total: nothing about another company's homes leaves this method.
     */
    Positions positions(UUID org) {
        Ranking rk = ranking();
        Filter f = filter(Map.of());
        int cap = boostCap(rk.boost());
        List<Map<String, Object>> all = asSystem(() -> db.list("""
                select y.id, y.org_id, y.lifted, rank() over (order by y.score desc, y.published_at desc nulls last) as pos, count(*) over () as total
                from (select t.id, t.org_id, t.published_at, (t.boosted = 1 and t.brank <= """ + cap + """
) as lifted, """ + ListingRanking.finalScoreSql(rk.boost(), cap, "t.") + """
 as score
                      from (select l.id, l.org_id, l.published_at, f.boosted, """ + ListingRanking.scoreSql(rk.settings()) + " as base_score, " + ListingRanking.boostRankSql(rk.settings()) + """
 as brank
                            from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                            """ + ListingRanking.joins(rk.settings(), rk.tiers()) + " where " + f.where() + ") t) y", f.args().toArray()));
        Map<UUID, Integer> mine = new HashMap<>();
        Set<UUID> lifted = new HashSet<>();
        for (Map<String, Object> r : all) {
            if (!org.equals(r.get("org_id"))) continue;
            mine.put((UUID) r.get("id"), ((Number) r.get("pos")).intValue());
            if (Boolean.TRUE.equals(r.get("lifted"))) lifted.add((UUID) r.get("id"));
        }
        return new Positions(mine, lifted, all.isEmpty() ? 0 : ((Number) all.get(0).get("total")).intValue());
    }

    // ----------------------------------------------------------------------------------------------- filters

    /** A WHERE clause over listing {@code l}, unit {@code u} and property {@code p}, with its values. */
    private record Filter(String where, List<Object> args) {
    }

    private static Integer integer(Map<String, String> q, String key, int min, int max) {
        String v = q.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            int n = Integer.parseInt(v.trim());
            if (n < min || n > max) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("\"" + key + "\" must be a whole number between " + min + " and " + max + ".");
        }
    }

    private static Double decimal(Map<String, String> q, String key) {
        String v = q.get(key);
        if (v == null || v.isBlank()) return null;
        try {
            double n = Double.parseDouble(v.trim());
            if (n < 0 || n > 10_000_000) throw new NumberFormatException();
            return n;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("\"" + key + "\" must be a number.");
        }
    }

    private Filter filter(Map<String, String> q) {
        StringBuilder w = new StringBuilder("l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))");
        List<Object> a = new ArrayList<>();
        a.add(suspended == null ? "" : suspended.asCsv());
        String kw = q.getOrDefault("keyword", "").trim();
        if (!kw.isEmpty()) {
            if (kw.length() > 80) throw ApiException.badRequest("Search for fewer than 80 characters.");
            String like = "%" + kw.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            w.append(" and (p.name ilike ? or p.street ilike ? or p.city ilike ? or p.zip ilike ? or l.title ilike ?)");
            for (int i = 0; i < 5; i++) a.add(like);
        }
        String city = q.getOrDefault("city", "").trim();
        if (!city.isEmpty()) { w.append(" and lower(p.city) like ?"); a.add("%" + city.toLowerCase() + "%"); }
        String state = q.getOrDefault("state", "").trim();
        if (!state.isEmpty()) { w.append(" and p.state = ?"); a.add(state.toUpperCase()); }
        String type = q.getOrDefault("type", "").trim();
        if (!type.isEmpty()) { w.append(" and p.type = ?"); a.add(type.toUpperCase()); }
        Integer beds = integer(q, "beds", 0, 20);
        if (beds != null) { w.append(" and u.beds >= ?"); a.add(beds); }
        Double baths = decimal(q, "baths");
        if (baths != null) { w.append(" and u.baths >= ?"); a.add(baths); }
        Double minRent = decimal(q, "minRent"), maxRent = decimal(q, "maxRent");
        if (minRent != null && maxRent != null && minRent > maxRent) throw ApiException.badRequest("The lowest rent is above the highest rent.");
        if (minRent != null) { w.append(" and l.rent >= ?"); a.add(minRent); }
        if (maxRent != null) { w.append(" and l.rent <= ?"); a.add(maxRent); }
        Integer minSqft = integer(q, "minSqft", 0, 100_000), maxSqft = integer(q, "maxSqft", 0, 100_000);
        if (minSqft != null && maxSqft != null && minSqft > maxSqft) throw ApiException.badRequest("The smallest size is above the largest size.");
        if (minSqft != null) { w.append(" and u.sqft >= ?"); a.add(minSqft); }
        if (maxSqft != null) { w.append(" and u.sqft <= ?"); a.add(maxSqft); }
        String by = q.getOrDefault("availableBy", "").trim();
        if (!by.isEmpty()) {
            try { LocalDate.parse(by); } catch (RuntimeException e) { throw ApiException.badRequest("\"availableBy\" must be a date such as 2026-12-01."); }
            w.append(" and l.available_date <= ?::date");
            a.add(by);
        }
        String am = q.getOrDefault("amenities", "").trim();
        if (!am.isEmpty()) {
            List<String> names = Arrays.stream(am.split(",")).map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
            if (names.size() > 20) throw ApiException.badRequest("Choose up to 20 amenities.");
            // A home must have every amenity asked for.
            w.append(" and p.amenities @> ?::jsonb");
            a.add(db.json(names));
        }
        return new Filter(w.toString(), a);
    }

    // ----------------------------------------------------------------------------------------------- the lists

    /** One row per home (listing). Paged when the request asks ({@code page}, {@code size}, {@code sort}, {@code dir}); otherwise all of them. */
    public List<Map<String, Object>> homes(Map<String, String> q) {
        Filter f = filter(q);
        Ranking rk = ranking();
        int cap = boostCap(rk.boost());
        List<Map<String, Object>> rows = asSystem(() -> {
            // The inner select scores each home and numbers the boosted ones; the outer adds the boost bonus to the boosted homes within the page cap.
            List<Map<String, Object>> page = db.page("""
                select x.*, """ + ListingRanking.finalScoreSql(rk.boost(), cap, "x.") + """
 as score
                from (select l.id, l.title, l.description, l.rent, l.available_date, l.photos, l.highlights, l.published_at, l.org_id::text as org_ref,
                       p.id as property_id, p.name as property_name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.amenities, p.type as property_type, p.year_built,
                       u.number as unit_number, u.beds, u.baths, u.sqft,
                       (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating,
                       (select count(*) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating_count,
                       """ + ListingRanking.ratingRankSql() + """
 as rating_rank, f.boosted, """ + ListingRanking.scoreSql(rk.settings()) + " as base_score, " + ListingRanking.boostRankSql(rk.settings()) + """
 as brank
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                """ + ListingRanking.joins(rk.settings(), rk.tiers()) + " where " + f.where() + ") x order by score desc, x.published_at desc nulls last", f.args().toArray());
            // The score, the smoothed rating and the boost bookkeeping only order the results; visitors see only whether a home is sponsored.
            page.forEach(r -> {
                r.put("sponsored", ((Number) r.get("boosted")).intValue() == 1);
                for (String k : List.of("score", "rating_rank", "base_score", "boosted", "brank")) r.remove(k);
            });
            return page;
        });
        withCompanyNames(rows);
        return rows;
    }

    /**
     * One row per property that has at least one available home matching the filters, with those homes listed inside it (their unit, size,
     * rent and date). Paged like {@link #homes}; sort by name, min_rent, units_available, next_available or published_at.
     */
    public List<Map<String, Object>> properties(Map<String, String> q) {
        Filter f = filter(q);
        Ranking rk = ranking();
        int cap = boostCap(rk.boost());
        List<Map<String, Object>> rows = asSystem(() -> {
            List<Map<String, Object>> page = db.page("""
                    with b as (
                      select l.rent, l.available_date, l.published_at, l.org_id::text as oid, u.beds, u.baths, u.sqft,
                             p.id as pid, p.name, p.street, p.city, p.state, p.zip, p.type, p.year_built, p.amenities, p.photos as pphotos,
                             """ + ListingRanking.ratingRankSql() + """
 as rating_rank, f.boosted, """ + ListingRanking.scoreSql(rk.settings()) + " as base_score, " + ListingRanking.boostRankSql(rk.settings()) + """
 as brank
                      from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                      """ + ListingRanking.joins(rk.settings(), rk.tiers()) + " where " + f.where() + """
                    ), m as (
                      select b.*, """ + ListingRanking.finalScoreSql(rk.boost(), cap, "b.") + """
 as score from b
                    )
                    select m.pid as id, max(m.name) as name, max(m.street) as street, max(m.city) as city, max(m.state) as state, max(m.zip) as zip,
                           max(m.type) as type, max(m.year_built) as year_built, (array_agg(m.amenities))[1] as amenities, (array_agg(m.pphotos))[1] as property_photos,
                           (array_agg(m.oid))[1] as org_ref, count(*) as units_available, min(m.rent) as min_rent, max(m.rent) as max_rent,
                           min(m.beds) as min_beds, max(m.beds) as max_beds, min(m.baths) as min_baths, max(m.baths) as max_baths,
                           min(m.sqft) as min_sqft, max(m.sqft) as max_sqft, min(m.available_date) as next_available, max(m.published_at) as published_at, max(m.score) as score, max(m.rating_rank) as rating_rank, max(m.boosted) as boosted,
                           (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = m.pid) as rating_avg,
                           (select count(*) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = m.pid) as rating_count
                    from m group by m.pid order by max(m.score) desc, max(m.published_at) desc nulls last""", f.args().toArray());
            page.forEach(r -> { // they only order the results; visitors see only whether a property has a sponsored home
                r.put("sponsored", ((Number) r.get("boosted")).intValue() == 1);
                for (String k : List.of("score", "rating_rank", "boosted")) r.remove(k);
            });
            if (page.isEmpty()) return page;
            // The homes inside each property on this page, found with the same filters.
            List<Object> args = new ArrayList<>(f.args());
            StringBuilder in = new StringBuilder();
            for (Map<String, Object> p : page) { in.append(in.length() == 0 ? "?::uuid" : ",?::uuid"); args.add(String.valueOf(p.get("id"))); }
            List<Map<String, Object>> units = db.list("""
                    select l.id, l.property_id, u.number as unit_number, u.beds, u.baths, u.sqft, l.rent, l.available_date, l.photos
                    from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                    where """ + " " + f.where() + " and l.property_id in (" + in + ") order by u.beds, l.rent", args.toArray());
            Map<String, List<Map<String, Object>>> byProperty = new LinkedHashMap<>();
            for (Map<String, Object> u : units) {
                Map<String, Object> home = new LinkedHashMap<>();
                for (String k : List.of("id", "unit_number", "beds", "baths", "sqft", "rent", "available_date")) home.put(k, u.get(k));
                home.put("photo", firstPhoto(u.get("photos")));
                byProperty.computeIfAbsent(String.valueOf(u.get("property_id")), k -> new ArrayList<>()).add(home);
            }
            for (Map<String, Object> p : page) {
                List<Map<String, Object>> homes = byProperty.getOrDefault(String.valueOf(p.get("id")), List.of());
                p.put("units", homes);
                // The property's own photos first, then a picture of each home, so a card always has something to flip through.
                List<String> gallery = new ArrayList<>(strings(p.remove("property_photos")));
                for (Map<String, Object> h : homes) if (h.get("photo") != null && !gallery.contains(h.get("photo"))) gallery.add((String) h.get("photo"));
                p.put("photos", gallery.size() > 10 ? gallery.subList(0, 10) : gallery);
            }
            return page;
        });
        withCompanyNames(rows);
        return rows;
    }

    private List<String> strings(Object json) {
        List<String> out = new ArrayList<>();
        JsonNode n = json == null ? null : db.tree(json);
        if (n != null && n.isArray()) n.forEach(x -> { if (x.isTextual() && !x.asText().isBlank()) out.add(x.asText()); });
        return out;
    }

    private String firstPhoto(Object json) {
        List<String> all = strings(json);
        return all.isEmpty() ? null : all.get(0);
    }

    /** Adds {@code company_name} to each row and removes the internal company reference, which visitors never see. */
    private void withCompanyNames(List<Map<String, Object>> rows) {
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> r : rows) {
            Object ref = r.remove("org_ref");
            if (ref == null) continue;
            r.put("company_name", names.computeIfAbsent(ref.toString(), id -> branding(UUID.fromString(id)).get("name").toString()));
        }
    }

    // ----------------------------------------------------------------------------------------------- the company

    /** Who is renting a home out: the company's public name and contact details, from its Branding settings. */
    Map<String, Object> branding(UUID org) {
        JsonNode b = config.forOrg(org).path("branding");
        Map<String, Object> out = new LinkedHashMap<>();
        String name = b.path("companyName").asText("").trim();
        out.put("name", name.isEmpty() ? "Property management company" : name);
        for (String k : List.of("contactPhone", "contactEmail", "website")) out.put(k, b.path(k).asText("").isBlank() ? null : b.path(k).asText().trim());
        String logo = b.path("logoDataUrl").asText("");
        out.put("logo", logo.isBlank() ? null : logo);
        return out;
    }

    /**
     * What the detail page adds to a home: who is renting it out, the other available homes in the same property, and the icon of each of
     * its amenities. Runs inside the caller's system transaction.
     */
    Map<String, Object> detailExtras(UUID listing, UUID org, UUID property, Object amenities) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("company", branding(org));
        List<Map<String, Object>> siblings = db.list("""
                select l.id, u.number as unit_number, u.beds, u.baths, u.sqft, l.rent, l.available_date, l.photos
                from leasing.listing l join leasing.unit u on u.id = l.unit_id
                where l.property_id = ? and l.status = 'LIVE' and l.id <> ? order by u.beds, l.rent""", property, listing);
        List<Map<String, Object>> others = new ArrayList<>();
        for (Map<String, Object> s : siblings) {
            Map<String, Object> m = new LinkedHashMap<>();
            for (String k : List.of("id", "unit_number", "beds", "baths", "sqft", "rent", "available_date")) m.put(k, s.get(k));
            m.put("photo", firstPhoto(s.get("photos")));
            others.add(m);
        }
        out.put("other_homes", others);
        Map<String, String> icons = new LinkedHashMap<>();
        Set<String> wanted = new HashSet<>();
        for (String a : strings(amenities)) wanted.add(a.toLowerCase());
        for (Map<String, Object> a : db.list("select name, icon, org_id from leasing.amenity where active and (org_id is null or org_id = ?) order by org_id nulls last", org))
            if (wanted.contains(String.valueOf(a.get("name")).toLowerCase())) icons.put(String.valueOf(a.get("name")), String.valueOf(a.get("icon")));
        out.put("amenity_icons", icons);
        return out;
    }

    // ----------------------------------------------------------------------------------------------- the filter choices

    /** The choices worth offering in the filters, taken from what is really available: cities, states, property types, amenities (with how many homes have each), and the rent and size ranges. */
    public Map<String, Object> facets() {
        return asSystem(() -> {
            String live = " from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id where l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))";
            Object csv = suspended == null ? "" : suspended.asCsv();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("cities", db.list("select p.city as name, p.state, count(*) as homes" + live + " group by p.city, p.state order by count(*) desc, p.city", csv));
            out.put("states", db.list("select p.state as name, count(*) as homes" + live + " group by p.state order by p.state", csv));
            out.put("types", db.list("select p.type as name, count(*) as homes" + live + " group by p.type order by count(*) desc", csv));
            out.put("beds", db.list("select u.beds as name, count(*) as homes" + live + " group by u.beds order by u.beds", csv));
            out.put("amenities", db.list("""
                    select a.name, count(*) as homes,
                           (select am.icon from leasing.amenity am where lower(am.name) = lower(a.name) and am.active order by am.org_id nulls first limit 1) as icon
                    """ + live.replace(" where ", ", jsonb_array_elements_text(p.amenities) as a(name) where ") + " group by a.name order by count(*) desc, a.name", csv));
            out.putAll(db.list("select coalesce(min(l.rent), 0) as min_rent, coalesce(max(l.rent), 0) as max_rent, coalesce(min(u.sqft), 0) as min_sqft, coalesce(max(u.sqft), 0) as max_sqft, count(*) as homes"
                    + ", count(distinct p.id) as properties" + live, csv).get(0));
            return out;
        });
    }
}
