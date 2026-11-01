package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The amenity catalogue (the platform's list and each company's own) and the public homes search: filters, paging, the by-property view with the
 * homes inside each property, the filter choices, and what a visitor learns about the company renting a home out.
 */
class PublicCatalogIT {
    private final ApiClient leasing = Flow.leasing();
    private static final String PUBLIC = "/api/leasing/public";

    private static List<String> names(JsonNode list) {
        List<String> out = new ArrayList<>();
        for (JsonNode n : list) out.add(n.path("name").asText());
        return out;
    }

    @Test
    void companiesAddTheirOwnAmenitiesAndThePlatformKeepsTheSharedList() {
        String admin = Flow.token("ORG_ADMIN"), tenant = Flow.token("TENANT"), platform = Flow.token("PLATFORM_ADMIN");
        String sfx = UUID.randomUUID().toString().substring(0, 6);

        // Everyone starts from the platform's list, each entry with an icon.
        JsonNode offered = Flow.ok(leasing.get(admin, "/api/leasing/amenities"), "amenities offered").body();
        assertTrue(offered.size() >= 20, "the platform list is seeded");
        assertTrue(names(offered).contains("Covered parking"));
        for (JsonNode a : offered) assertFalse(a.path("icon").asText().isBlank(), a.path("name").asText() + " has an icon");

        // A company adds its own; it shows for the company only, and cannot repeat a platform name.
        String mine = "Dog wash " + sfx;
        Reply added = Flow.ok(leasing.post(admin, "/api/leasing/amenities", Map.of("name", mine, "icon", "Dog")), "add amenity");
        String id = added.body().path("id").asText();
        assertEquals(409, leasing.post(admin, "/api/leasing/amenities", Map.of("name", mine.toUpperCase(), "icon", "Dog")).status(), "same name, any case");
        assertEquals(409, leasing.post(admin, "/api/leasing/amenities", Map.of("name", "covered PARKING", "icon", "Car")).status(), "a platform name");
        assertEquals(400, leasing.post(admin, "/api/leasing/amenities", Map.of("name", "x", "icon", "Dog")).status(), "name too short");
        assertEquals(400, leasing.post(admin, "/api/leasing/amenities", Map.of("name", "Bad icon " + sfx, "icon", "not an icon!")).status());
        assertEquals(400, leasing.post(admin, "/api/leasing/amenities", Map.of("name", "No icon " + sfx)).status());
        assertTrue(names(leasing.get(admin, "/api/leasing/amenities").body()).contains(mine));
        assertEquals(List.of(mine), names(leasing.get(admin, "/api/leasing/amenities/mine").body()).stream().filter(n -> n.endsWith(sfx)).toList());
        assertEquals(403, leasing.post(tenant, "/api/leasing/amenities", Map.of("name", "Resident idea " + sfx, "icon", "Dog")).status(), "residents cannot add");
        assertEquals(401, leasing.get(null, "/api/leasing/amenities").status());

        // Change the icon, switch it off (no longer offered), remove it.
        Flow.ok(leasing.put(admin, "/api/leasing/amenities/" + id, Map.of("icon", "PawPrint")), "change icon");
        Flow.ok(leasing.put(admin, "/api/leasing/amenities/" + id, Map.of("active", false)), "switch off");
        assertFalse(names(leasing.get(admin, "/api/leasing/amenities").body()).contains(mine), "switched-off amenities are not offered");
        assertEquals(409, leasing.put(admin, "/api/leasing/amenities/" + id, Map.of("name", "Garage")).status(), "renaming onto a platform name");
        assertEquals(404, leasing.put(admin, "/api/leasing/amenities/" + UUID.randomUUID(), Map.of("icon", "Dog")).status());

        // A company cannot change the platform's entries, and cannot reach the platform's endpoints.
        String platformEntry = offered.get(0).path("id").asText();
        assertEquals(403, leasing.put(admin, "/api/leasing/amenities/" + platformEntry, Map.of("icon", "Dog")).status());
        assertEquals(403, leasing.delete(admin, "/api/leasing/amenities/" + platformEntry).status());
        assertEquals(403, leasing.get(admin, "/api/leasing/platform/amenities").status());
        assertEquals(403, leasing.post(admin, "/api/leasing/platform/amenities", Map.of("name", "Sneaky " + sfx, "icon", "Dog")).status());
        Flow.ok(leasing.delete(admin, "/api/leasing/amenities/" + id), "remove own");
        assertEquals(404, leasing.delete(admin, "/api/leasing/amenities/" + id).status());

        // The platform administrator keeps the shared list, and cannot edit a company's own entry from there.
        String shared = "Cold plunge " + sfx;
        String sid = Flow.ok(leasing.post(platform, "/api/leasing/platform/amenities", Map.of("name", shared, "icon", "Waves")), "platform add").body().path("id").asText();
        assertTrue(names(leasing.get(platform, "/api/leasing/platform/amenities").body()).contains(shared));
        assertTrue(names(leasing.get(admin, "/api/leasing/amenities").body()).contains(shared), "companies see what the platform adds");
        assertEquals(403, leasing.put(admin, "/api/leasing/amenities/" + sid, Map.of("icon", "Dog")).status());
        Flow.ok(leasing.put(platform, "/api/leasing/platform/amenities/" + sid, Map.of("icon", "Snowflake")), "platform change");
        String own = Flow.ok(leasing.post(admin, "/api/leasing/amenities", Map.of("name", "Own " + sfx, "icon", "Dog")), "company entry").body().path("id").asText();
        assertEquals(403, leasing.put(platform, "/api/leasing/platform/amenities/" + own, Map.of("icon", "Sun")).status(), "a company's entry is its own");
        Flow.ok(leasing.delete(admin, "/api/leasing/amenities/" + own), "tidy company entry");
        Flow.ok(leasing.delete(platform, "/api/leasing/platform/amenities/" + sid), "platform remove");
        assertFalse(names(leasing.get(admin, "/api/leasing/amenities").body()).contains(shared));
    }

    @Test
    void visitorsSearchHomesByPropertyOrOneByOneWithFiltersAndPages() {
        // The choices for the filters come from what is really available.
        JsonNode facets = Flow.ok(leasing.get(null, PUBLIC + "/facets"), "facets").body();
        assertTrue(facets.path("homes").asInt() > 0, "demo data has homes to rent");
        assertTrue(facets.path("cities").size() > 0 && facets.path("types").size() > 0 && facets.path("amenities").size() > 0);
        assertTrue(facets.path("max_rent").asDouble() >= facets.path("min_rent").asDouble());
        String city = facets.path("cities").get(0).path("name").asText();
        String amenity = facets.path("amenities").get(0).path("name").asText();

        // The by-property list: one card per property, with the homes inside it.
        Reply page = Flow.ok(leasing.get(null, PUBLIC + "/properties?page=1&size=2&sort=min_rent&dir=asc"), "properties page");
        JsonNode body = page.body();
        assertEquals(1, body.path("page").asInt());
        assertEquals(2, body.path("size").asInt());
        assertTrue(body.path("total").asInt() >= body.path("rows").size());
        assertTrue(body.path("rows").size() <= 2);
        double previous = -1;
        for (JsonNode p : body.path("rows")) {
            assertTrue(p.path("units").size() > 0, "each property lists its available homes");
            assertEquals(p.path("units_available").asInt(), p.path("units").size());
            assertTrue(p.path("min_rent").asDouble() <= p.path("max_rent").asDouble());
            assertTrue(p.path("min_rent").asDouble() >= previous, "sorted by lowest rent");
            previous = p.path("min_rent").asDouble();
            assertFalse(p.path("company_name").asText().isBlank(), "a visitor sees who is renting it out");
            assertTrue(p.has("photos") && p.has("rating_count") && p.has("amenities"));
            for (String leaked : List.of("org_id", "org_ref", "owner_id")) assertFalse(p.has(leaked), "no " + leaked + " in a public row");
            for (JsonNode u : p.path("units")) {
                assertFalse(u.path("id").asText().isBlank());
                assertTrue(u.path("rent").asDouble() > 0);
            }
        }
        // Without a page the list is a plain array, for older callers.
        assertTrue(leasing.get(null, PUBLIC + "/properties").body().isArray());
        // Past the last page is simply empty.
        assertEquals(0, Flow.ok(leasing.get(null, PUBLIC + "/properties?page=999&size=2"), "past the end").body().path("rows").size());

        // The all-homes list pages and sorts too.
        JsonNode homes = Flow.ok(leasing.get(null, PUBLIC + "/listings?page=1&size=3&sort=rent&dir=desc"), "homes page").body();
        assertTrue(homes.path("rows").size() <= 3);
        double last = Double.MAX_VALUE;
        for (JsonNode h : homes.path("rows")) {
            assertTrue(h.path("rent").asDouble() <= last, "rent high to low");
            last = h.path("rent").asDouble();
            assertFalse(h.has("org_ref") || h.has("org_id") || h.has("owner_id"));
            assertFalse(h.path("company_name").asText().isBlank());
        }

        // Filters narrow the result; every row returned really meets them.
        for (JsonNode h : Flow.ok(leasing.get(null, PUBLIC + "/listings?beds=2&baths=2&minRent=1000&maxRent=4000&minSqft=500&maxSqft=3000"), "combined").body()) {
            assertTrue(h.path("beds").asInt() >= 2 && h.path("baths").asDouble() >= 2);
            assertTrue(h.path("rent").asDouble() >= 1000 && h.path("rent").asDouble() <= 4000);
            assertTrue(h.path("sqft").asInt() >= 500 && h.path("sqft").asInt() <= 3000);
        }
        JsonNode inCity = Flow.ok(leasing.get(null, PUBLIC + "/listings?city=" + city.toLowerCase()), "city, any case").body();
        assertTrue(inCity.size() > 0);
        for (JsonNode h : inCity) assertEquals(city, h.path("city").asText());
        assertEquals(0, Flow.ok(leasing.get(null, PUBLIC + "/listings?city=Nowhereville"), "no such city").body().size());
        JsonNode withAmenity = Flow.ok(leasing.get(null, PUBLIC + "/properties?amenities=" + amenity), "amenity").body();
        assertTrue(withAmenity.size() > 0, "properties with amenity [" + amenity + "], facets " + facets.path("amenities"));
        for (JsonNode p : withAmenity) assertTrue(contains(p.path("amenities"), amenity), "every property returned has " + amenity);
        assertEquals(0, Flow.ok(leasing.get(null, PUBLIC + "/properties?amenities=" + amenity + ",Not a real amenity"), "needs every amenity").body().size());
        assertTrue(Flow.ok(leasing.get(null, PUBLIC + "/listings?keyword=" + facets.path("cities").get(0).path("name").asText()), "keyword").body().size() > 0);
        assertEquals(0, Flow.ok(leasing.get(null, PUBLIC + "/listings?availableBy=2000-01-01"), "available by a past date").body().size());
        assertTrue(Flow.ok(leasing.get(null, PUBLIC + "/listings?availableBy=2999-01-01"), "available by a far date").body().size() > 0);
        assertTrue(Flow.ok(leasing.get(null, PUBLIC + "/listings?type=" + facets.path("types").get(0).path("name").asText()), "type").body().size() > 0);
        // % and _ are searched as ordinary characters, not wildcards.
        assertEquals(0, Flow.ok(leasing.get(null, PUBLIC + "/listings?keyword=%"), "percent sign").body().size());

        // Mistakes in a filter are explained, not answered with an empty list or an error page.
        for (String bad : List.of("beds=many", "beds=99", "baths=x", "minRent=-5", "minRent=abc", "minRent=3000&maxRent=1000", "minSqft=900&maxSqft=100", "availableBy=soon",
                "keyword=" + "x".repeat(81), "amenities=" + String.join(",", java.util.stream.IntStream.range(0, 25).mapToObj(i -> "Amenity" + i).toList())))
            assertEquals(400, leasing.get(null, PUBLIC + "/listings?" + bad).status(), bad);
    }

    private static boolean contains(JsonNode array, String value) {
        for (JsonNode n : array) if (value.equals(n.asText())) return true;
        return false;
    }

    @Test
    void aHomesPageNamesTheCompanyShowsTheOtherHomesAndTheAmenityIcons() {
        String admin = Flow.token("ORG_ADMIN");
        String sfx = UUID.randomUUID().toString().substring(0, 6);
        // The company's contact details are what visitors see; a value that is not a phone number, address or web link is refused when saved.
        ApiClient identity = Flow.identity();
        assertEquals(400, identity.put(admin, "/api/identity/config/branding", Map.of("contactPhone", "call me")).status(), "a phone number must look like one");
        assertEquals(400, identity.put(admin, "/api/identity/config/branding", Map.of("contactEmail", "not an email")).status());
        assertEquals(400, identity.put(admin, "/api/identity/config/branding", Map.of("website", "javascript:alert(1)")).status(), "only web addresses");
        JsonNode before = Flow.ok(identity.get(admin, "/api/identity/config"), "config").body().path("values").path("branding");
        java.util.Map<String, Object> changed = new java.util.LinkedHashMap<>();
        before.fields().forEachRemaining(e -> changed.put(e.getKey(), e.getValue().asText()));
        changed.put("contactPhone", "(512) 555-0142");
        changed.put("contactEmail", "leasing" + sfx + "@example.com");
        changed.put("website", "https://example.com/" + sfx);
        try {
            Flow.ok(identity.put(admin, "/api/identity/config/branding?summary=contact+test", changed), "save contact details");
            JsonNode first = Flow.ok(leasing.get(null, PUBLIC + "/properties?page=1&size=20"), "properties").body().path("rows").get(0);
            String org = first.path("company_name").asText();
            assertFalse(org.isBlank());
        } finally {
            java.util.Map<String, Object> restore = new java.util.LinkedHashMap<>();
            before.fields().forEachRemaining(e -> restore.put(e.getKey(), e.getValue().asText()));
            Flow.ok(identity.put(admin, "/api/identity/config/branding?summary=contact+test+restored", restore), "restore");
        }

        JsonNode rows = Flow.ok(leasing.get(null, PUBLIC + "/properties?page=1&size=20"), "properties").body().path("rows");
        JsonNode multi = null;
        for (JsonNode p : rows) if (p.path("units").size() > 1) { multi = p; break; }
        JsonNode any = multi != null ? multi : rows.get(0);
        String home = any.path("units").get(0).path("id").asText();
        JsonNode detail = Flow.ok(leasing.get(null, PUBLIC + "/listings/" + home), "detail").body();
        assertFalse(detail.path("company").path("name").asText().isBlank(), "who is renting it out");
                assertTrue(detail.path("other_homes").isArray());
        assertTrue(detail.path("amenity_icons").isObject());
        for (String hidden : List.of("org_ref", "property_ref", "org_id", "owner_id")) assertFalse(detail.has(hidden), "no " + hidden + " on the detail page");
        if (multi != null) {
            assertEquals(multi.path("units").size() - 1, detail.path("other_homes").size(), "the other available homes in the same property");
            for (JsonNode o : detail.path("other_homes")) assertFalse(home.equals(o.path("id").asText()), "not itself");
        }
    }
}
