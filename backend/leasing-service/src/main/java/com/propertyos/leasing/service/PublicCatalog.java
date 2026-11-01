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
        List<Map<String, Object>> rows = asSystem(() -> db.page("""
                select l.id, l.title, l.description, l.rent, l.available_date, l.photos, l.highlights, l.published_at, l.org_id::text as org_ref,
                       p.id as property_id, p.name as property_name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.amenities, p.type as property_type, p.year_built,
                       u.number as unit_number, u.beds, u.baths, u.sqft,
                       (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating,
                       (select count(*) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = p.id) as property_rating_count
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                where """ + " " + f.where() + " order by l.published_at desc nulls last", f.args().toArray()));
        withCompanyNames(rows);
        return rows;
    }

    /**
     * One row per property that has at least one available home matching the filters, with those homes listed inside it (their unit, size,
     * rent and date). Paged like {@link #homes}; sort by name, min_rent, units_available, next_available or published_at.
     */
    public List<Map<String, Object>> properties(Map<String, String> q) {
        Filter f = filter(q);
        List<Map<String, Object>> rows = asSystem(() -> {
            List<Map<String, Object>> page = db.page("""
                    with m as (
                      select l.rent, l.available_date, l.published_at, l.org_id::text as oid, u.beds, u.baths, u.sqft,
                             p.id as pid, p.name, p.street, p.city, p.state, p.zip, p.type, p.year_built, p.amenities, p.photos as pphotos
                      from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                      where """ + " " + f.where() + """
                    )
                    select m.pid as id, max(m.name) as name, max(m.street) as street, max(m.city) as city, max(m.state) as state, max(m.zip) as zip,
                           max(m.type) as type, max(m.year_built) as year_built, (array_agg(m.amenities))[1] as amenities, (array_agg(m.pphotos))[1] as property_photos,
                           (array_agg(m.oid))[1] as org_ref, count(*) as units_available, min(m.rent) as min_rent, max(m.rent) as max_rent,
                           min(m.beds) as min_beds, max(m.beds) as max_beds, min(m.baths) as min_baths, max(m.baths) as max_baths,
                           min(m.sqft) as min_sqft, max(m.sqft) as max_sqft, min(m.available_date) as next_available, max(m.published_at) as published_at,
                           (select round(avg(r.stars), 1) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = m.pid) as rating_avg,
                           (select count(*) from leasing.review r where r.subject_type = 'PROPERTY' and r.subject_id = m.pid) as rating_count
                    from m group by m.pid order by max(m.published_at) desc nulls last""", f.args().toArray());
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
