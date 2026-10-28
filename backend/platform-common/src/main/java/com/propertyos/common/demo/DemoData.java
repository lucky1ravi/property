package com.propertyos.common.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Shared demo fixture. Every service seeds its own tables from this one definition, so
 * properties, leases, owners and vendors line up across services without startup calls.
 * Ids are deterministic (name-based UUIDs), which also makes "reset demo data" repeatable.
 */
public final class DemoData {
    private DemoData() {
    }

    public static UUID id(String key) {
        return UUID.nameUUIDFromBytes(("propertyos:" + key).getBytes(StandardCharsets.UTF_8));
    }

    // ---------- Organisations ----------
    public record Org(UUID id, String name, String legalName, String state, String plan) {
    }

    public static final Org PLATFORM = new Org(id("org:platform"), "PropertyOS Platform", "PropertyOS Inc.", "DE", "ENTERPRISE");
    public static final Org SUMMIT = new Org(id("org:summit"), "Summit Ridge Property Group", "Summit Ridge Property Group LLC", "TX", "ENTERPRISE");
    public static final Org HARBOR = new Org(id("org:harbor"), "Harborview Residential", "Harborview Residential Inc.", "CA", "GROWTH");

    public static List<Org> orgs() {
        return List.of(PLATFORM, SUMMIT, HARBOR);
    }

    // ---------- Owners ----------
    public record Owner(UUID id, UUID orgId, String name, String contact, String email, String phone,
                        BigDecimal approvalLimit, BigDecimal managementFeePercent, String bankLast4) {
    }

    public static final Owner ROBERT = new Owner(id("owner:robert"), SUMMIT.id(), "Robert Williams", "Robert Williams",
            "robert.williams@example.com", "(512) 555-0142", bd(500), bd(8), "4471");
    public static final Owner JAMES = new Owner(id("owner:james"), SUMMIT.id(), "James Carter", "James Carter",
            "james.carter@example.com", "(512) 555-0188", bd(750), bd(8), "2290");
    public static final Owner LINDA = new Owner(id("owner:linda"), SUMMIT.id(), "Linda Thompson", "Linda Thompson",
            "linda.thompson@example.com", "(619) 555-0117", bd(400), bd(8.5), "9034");
    public static final Owner GREENFIELD = new Owner(id("owner:greenfield"), SUMMIT.id(), "Greenfield Capital LLC", "Nathan Greene",
            "nathan.greene@greenfieldcap.com", "(206) 555-0163", bd(1500), bd(7), "6612");
    public static final Owner HARBOR_OWNER = new Owner(id("owner:harbor-mei"), HARBOR.id(), "Mei Tanaka", "Mei Tanaka",
            "mei.tanaka@example.com", "(415) 555-0190", bd(600), bd(9), "1180");

    public static List<Owner> owners() {
        return List.of(ROBERT, JAMES, LINDA, GREENFIELD, HARBOR_OWNER);
    }

    // ---------- Users ----------
    public record User(UUID id, UUID orgId, String name, String email, String title, String roleKey,
                       UUID partyId, List<UUID> propertyIds, String persona) {
    }

    // ---------- Properties and units ----------
    public record Property(UUID id, UUID orgId, String name, String street, String city, String state, String zip,
                           double lat, double lng, String type, UUID ownerId, int yearBuilt, List<String> amenities,
                           String brokerKey, String photo) {
    }

    public record Unit(UUID id, UUID propertyId, String number, int beds, double baths, int sqft, BigDecimal marketRent) {
    }

    public static final Property DOMAIN = prop("domain", SUMMIT, "The Domain Lofts", "11500 Rock Rose Ave", "Austin", "TX", "78758",
            30.4021, -97.7253, "MULTIFAMILY", ROBERT, 2016, List.of("Pool", "Fitness center", "Covered parking", "In-unit laundry"), "marcus",
            "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?w=1200&q=80");
    public static final Property BARTON = prop("barton", SUMMIT, "Barton Creek Townhomes", "2700 S Lamar Blvd", "Austin", "TX", "78704",
            30.2468, -97.7813, "TOWNHOME", ROBERT, 2012, List.of("Private garage", "Patio", "Pet friendly"), "marcus",
            "https://images.unsplash.com/photo-1600596542815-ffad4c1539a9?w=1200&q=80");
    public static final Property MUELLER = prop("mueller", SUMMIT, "Mueller Park Duplexes", "1900 Aldrich St", "Austin", "TX", "78723",
            30.2983, -97.7059, "DUPLEX", JAMES, 2018, List.of("Yard", "Solar panels", "EV charger"), "marcus",
            "https://images.unsplash.com/photo-1570129477492-45c003edd2be?w=1200&q=80");
    public static final Property PACIFIC = prop("pacific", SUMMIT, "Pacific Crest Apartments", "3550 Front St", "San Diego", "CA", "92103",
            32.7426, -117.1663, "MULTIFAMILY", LINDA, 2009, List.of("Ocean view", "Rooftop deck", "Bike storage"), "emily",
            "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=1200&q=80");
    public static final Property CAPITOL = prop("capitol", SUMMIT, "Capitol Hill Flats", "1520 E Pine St", "Seattle", "WA", "98122",
            47.6153, -122.3127, "MULTIFAMILY", GREENFIELD, 2014, List.of("Walk score 97", "Bike room", "Package lockers"), "emily",
            "https://images.unsplash.com/photo-1460317442991-0ec209397118?w=1200&q=80");
    public static final Property LAKEUNION = prop("lakeunion", SUMMIT, "Lake Union Residences", "901 Fairview Ave N", "Seattle", "WA", "98109",
            47.6275, -122.3335, "MULTIFAMILY", GREENFIELD, 2020, List.of("Lake view", "Concierge", "Fitness center", "EV charging"), "emily",
            "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=1200&q=80");
    public static final Property HARBOR_PROP = prop("harbor-sunset", HARBOR, "Sunset Terrace", "1450 Irving St", "San Francisco", "CA", "94122",
            37.7638, -122.4750, "MULTIFAMILY", HARBOR_OWNER, 1998, List.of("Garden", "Laundry room"), "kevin",
            "https://images.unsplash.com/photo-1494526585095-c41746248156?w=1200&q=80");

    public static List<Property> properties() {
        return List.of(DOMAIN, BARTON, MUELLER, PACIFIC, CAPITOL, LAKEUNION, HARBOR_PROP);
    }

    public static List<Unit> units() {
        List<Unit> u = new ArrayList<>();
        u.add(unit(DOMAIN, "101", 1, 1, 750, 1650));
        u.add(unit(DOMAIN, "102", 1, 1, 740, 1625));
        u.add(unit(DOMAIN, "201", 2, 2, 1100, 2150));
        u.add(unit(DOMAIN, "202", 2, 2, 1120, 2195));
        u.add(unit(DOMAIN, "301", 0, 1, 520, 1295));
        u.add(unit(DOMAIN, "302", 2, 2, 1180, 2250));
        u.add(unit(BARTON, "A", 3, 2.5, 1650, 2850));
        u.add(unit(BARTON, "B", 3, 2.5, 1680, 2895));
        u.add(unit(BARTON, "C", 2, 2, 1350, 2400));
        u.add(unit(BARTON, "D", 3, 2.5, 1700, 2900));
        u.add(unit(MUELLER, "1", 2, 2, 1200, 2100));
        u.add(unit(MUELLER, "2", 2, 2, 1220, 2125));
        u.add(unit(PACIFIC, "1A", 1, 1, 680, 2450));
        u.add(unit(PACIFIC, "1B", 1, 1, 690, 2475));
        u.add(unit(PACIFIC, "2A", 2, 2, 980, 3200));
        u.add(unit(PACIFIC, "2B", 2, 2, 1010, 3250));
        u.add(unit(PACIFIC, "3A", 3, 2, 1340, 4100));
        u.add(unit(CAPITOL, "1", 0, 1, 480, 1550));
        u.add(unit(CAPITOL, "2", 1, 1, 640, 1950));
        u.add(unit(CAPITOL, "3", 1, 1, 655, 1995));
        u.add(unit(CAPITOL, "4", 2, 1, 900, 2700));
        u.add(unit(LAKEUNION, "101", 2, 2, 1050, 3100));
        u.add(unit(LAKEUNION, "201", 2, 2, 1060, 3150));
        u.add(unit(LAKEUNION, "301", 3, 2, 1380, 3950));
        u.add(unit(HARBOR_PROP, "1", 1, 1, 650, 2950));
        u.add(unit(HARBOR_PROP, "2", 2, 1, 900, 3700));
        u.add(unit(HARBOR_PROP, "3", 2, 1, 920, 3750));
        return u;
    }

    public static Unit unit(String propertyKey, String number) {
        return units().stream().filter(x -> x.id().equals(id("unit:" + propertyKey + ":" + number))).findFirst().orElseThrow();
    }

    public static Property property(UUID id) {
        return properties().stream().filter(p -> p.id().equals(id)).findFirst().orElseThrow();
    }

    public static Optional<Owner> owner(UUID id) {
        return owners().stream().filter(o -> o.id().equals(id)).findFirst();
    }

    // ---------- Tenants (residents) ----------
    public record Tenant(UUID id, UUID orgId, String firstName, String lastName, String email, String phone,
                         int annualIncome, String employer) {
        public String name() {
            return firstName + " " + lastName;
        }
    }

    public static final List<Tenant> TENANTS = List.of(
            tenant("michael", "Michael", "Brown", "(512) 555-0201", 92000, "Dell Technologies"),
            tenant("jessica", "Jessica", "Wilson", "(512) 555-0202", 78000, "Ascension Seton"),
            tenant("daniel", "Daniel", "Martinez", "(512) 555-0203", 105000, "Oracle"),
            tenant("sophia", "Sophia", "Anderson", "(512) 555-0204", 61000, "H-E-B"),
            tenant("christopher", "Christopher", "Taylor", "(512) 555-0205", 148000, "Tesla"),
            tenant("amanda", "Amanda", "Thomas", "(512) 555-0206", 99000, "Austin ISD"),
            tenant("matthew", "Matthew", "Jackson", "(512) 555-0207", 112000, "Indeed"),
            tenant("olivia", "Olivia", "White", "(512) 555-0208", 88000, "University of Texas"),
            tenant("ethan", "Ethan", "Harris", "(619) 555-0209", 115000, "Qualcomm"),
            tenant("ashley", "Ashley", "Davis", "(619) 555-0210", 132000, "Sharp HealthCare"),
            tenant("ryan", "Ryan", "Clark", "(619) 555-0211", 96000, "Petco"),
            tenant("megan", "Megan", "Lewis", "(206) 555-0212", 104000, "Amazon"),
            tenant("joshua", "Joshua", "Walker", "(206) 555-0213", 125000, "Microsoft"),
            tenant("lauren", "Lauren", "Hall", "(206) 555-0214", 141000, "Starbucks"),
            tenant("andrew", "Andrew", "Young", "(206) 555-0215", 109000, "Expedia"),
            tenantOrg(HARBOR, "grace", "Grace", "Kim", "(415) 555-0216", 128000, "Salesforce")
    );

    public static Tenant tenant(String key) {
        return TENANTS.stream().filter(t -> t.id().equals(id("tenant:" + key))).findFirst().orElseThrow();
    }

    // ---------- Leases ----------
    /** payProfile: GOOD, LATE (sometimes late), DELINQUENT (currently behind). */
    public record Lease(UUID id, UUID orgId, UUID unitId, UUID propertyId, UUID ownerId, UUID tenantId,
                        LocalDate start, LocalDate end, BigDecimal rent, BigDecimal deposit, String payProfile) {
    }

    public static List<Lease> leases() {
        LocalDate today = LocalDate.now();
        List<Lease> l = new ArrayList<>();
        l.add(lease(DOMAIN, "101", "michael", today, -8, 1600, "GOOD"));
        l.add(lease(DOMAIN, "201", "jessica", today, -10, 2050, "LATE"));
        l.add(lease(DOMAIN, "202", "daniel", today, -3, 2195, "GOOD"));
        l.add(lease(DOMAIN, "302", "sophia", today, -6, 2150, "DELINQUENT"));
        l.add(lease(BARTON, "A", "christopher", today, -11, 2700, "GOOD"));
        l.add(lease(BARTON, "C", "amanda", today, -5, 2400, "GOOD"));
        l.add(lease(BARTON, "D", "matthew", today, -7, 2800, "LATE"));
        l.add(lease(MUELLER, "1", "olivia", today, -2, 2100, "GOOD"));
        l.add(lease(PACIFIC, "1A", "ethan", today, -9, 2300, "GOOD"));
        l.add(lease(PACIFIC, "2B", "ashley", today, -4, 3250, "GOOD"));
        l.add(lease(PACIFIC, "3A", "ryan", today, -10, 3900, "DELINQUENT"));
        l.add(lease(CAPITOL, "2", "megan", today, -6, 1900, "GOOD"));
        l.add(lease(CAPITOL, "4", "joshua", today, -9, 2550, "GOOD"));
        l.add(lease(LAKEUNION, "101", "lauren", today, -1, 3100, "GOOD"));
        l.add(lease(LAKEUNION, "301", "andrew", today, -8, 3850, "LATE"));
        l.add(lease(HARBOR_PROP, "2", "grace", today, -5, 3650, "GOOD"));
        return l;
    }

    public static Optional<Lease> leaseForTenant(UUID tenantId) {
        return leases().stream().filter(x -> x.tenantId().equals(tenantId)).findFirst();
    }

    // ---------- Vendors ----------
    public record Vendor(UUID id, UUID orgId, String name, String contact, List<String> trades, List<String> cities,
                         double rating, BigDecimal avgJobCost, String phone, String email, int jobsCompleted) {
    }

    public static final List<Vendor> VENDORS = List.of(
            vendor("lonestar", "Lone Star Plumbing", "Carlos Ramirez", List.of("PLUMBING"), List.of("Austin"), 4.8, 285, "(512) 555-0301", 142),
            vendor("austinhvac", "Austin HVAC Pros", "Brian Foster", List.of("HVAC"), List.of("Austin"), 4.6, 420, "(512) 555-0302", 98),
            vendor("brightspark", "BrightSpark Electric", "Tanya Brooks", List.of("ELECTRICAL"), List.of("Austin", "San Diego"), 4.7, 310, "(512) 555-0303", 76),
            vendor("coastal", "Coastal Home Services", "Diego Alvarez", List.of("PLUMBING", "APPLIANCE", "GENERAL"), List.of("San Diego"), 4.4, 260, "(619) 555-0304", 121),
            vendor("emerald", "Emerald City Handyman", "Sam Whitaker", List.of("GENERAL", "APPLIANCE", "PLUMBING"), List.of("Seattle"), 4.5, 240, "(206) 555-0305", 88),
            vendor("pestaway", "PestAway Solutions", "Hannah Lowe", List.of("PEST"), List.of("Austin", "San Diego", "Seattle"), 4.3, 180, "(800) 555-0306", 204),
            vendor("cascade", "Cascade Heating & Air", "Owen Mercer", List.of("HVAC"), List.of("Seattle"), 4.9, 455, "(206) 555-0307", 64)
    );

    public static Vendor vendor(String key) {
        return VENDORS.stream().filter(v -> v.id().equals(id("vendor:" + key))).findFirst().orElseThrow();
    }

    // ---------- Users ----------
    public static List<User> users() {
        List<UUID> austin = List.of(DOMAIN.id(), BARTON.id(), MUELLER.id());
        List<UUID> westCoast = List.of(PACIFIC.id(), CAPITOL.id(), LAKEUNION.id());
        return List.of(
                new User(id("user:alex"), PLATFORM.id(), "Alex Morgan", "alex.morgan@propertyos.io", "Platform Administrator", "PLATFORM_ADMIN", null, List.of(), "Platform admin"),
                new User(id("user:sarah"), SUMMIT.id(), "Sarah Mitchell", "sarah.mitchell@summitridgepg.com", "Chief Executive Officer", "ORG_ADMIN", null, List.of(), "Company owner / admin"),
                new User(id("user:david"), SUMMIT.id(), "David Chen", "david.chen@summitridgepg.com", "Director of Property Management", "PROPERTY_MANAGER", null, List.of(), "Property manager"),
                new User(id("user:marcus"), SUMMIT.id(), "Marcus Johnson", "marcus.johnson@summitridgepg.com", "Broker, Austin portfolio", "BROKER", null, austin, "Broker"),
                new User(id("user:emily"), SUMMIT.id(), "Emily Rodriguez", "emily.rodriguez@summitridgepg.com", "Broker, West Coast portfolio", "BROKER", null, westCoast, "Broker"),
                new User(id("user:jordan"), SUMMIT.id(), "Jordan Lee", "jordan.lee@summitridgepg.com", "Leasing Agent", "LEASING_AGENT", null, austin, "Leasing agent"),
                new User(id("user:priya"), SUMMIT.id(), "Priya Patel", "priya.patel@summitridgepg.com", "Controller", "ACCOUNTANT", null, List.of(), "Accountant"),
                new User(id("user:robert"), SUMMIT.id(), "Robert Williams", ROBERT.email(), "Property Owner", "OWNER", ROBERT.id(), List.of(), "Property owner"),
                new User(id("user:nathan"), SUMMIT.id(), "Nathan Greene", GREENFIELD.email(), "Managing Partner, Greenfield Capital", "OWNER", GREENFIELD.id(), List.of(), "Property owner"),
                new User(id("user:michael"), SUMMIT.id(), "Michael Brown", tenant("michael").email(), "Resident, Domain Lofts 101", "TENANT", tenant("michael").id(), List.of(), "Tenant"),
                new User(id("user:ashley"), SUMMIT.id(), "Ashley Davis", tenant("ashley").email(), "Resident, Pacific Crest 2B", "TENANT", tenant("ashley").id(), List.of(), "Tenant"),
                new User(id("user:carlos"), SUMMIT.id(), "Carlos Ramirez", "carlos@lonestarplumbing.com", "Owner, Lone Star Plumbing", "VENDOR", vendor("lonestar").id(), List.of(), "Vendor"),
                new User(id("user:kevin"), HARBOR.id(), "Kevin Park", "kevin.park@harborviewres.com", "Principal", "ORG_ADMIN", null, List.of(), "Company owner / admin (second company)")
        );
    }

    // ---------- helpers ----------
    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Property prop(String key, Org org, String name, String street, String city, String state, String zip,
                                 double lat, double lng, String type, Owner owner, int year, List<String> amenities,
                                 String brokerKey, String photo) {
        return new Property(id("property:" + key), org.id(), name, street, city, state, zip, lat, lng, type, owner.id(), year,
                amenities, brokerKey, photo);
    }

    private static Unit unit(Property p, String number, int beds, double baths, int sqft, int rent) {
        String key = propertyKey(p);
        return new Unit(id("unit:" + key + ":" + number), p.id(), number, beds, baths, sqft, bd(rent));
    }

    private static String propertyKey(Property p) {
        for (String k : List.of("domain", "barton", "mueller", "pacific", "capitol", "lakeunion", "harbor-sunset")) {
            if (id("property:" + k).equals(p.id())) return k;
        }
        throw new IllegalArgumentException(p.name());
    }

    private static Tenant tenant(String key, String first, String last, String phone, int income, String employer) {
        return tenantOrg(SUMMIT, key, first, last, phone, income, employer);
    }

    private static Tenant tenantOrg(Org org, String key, String first, String last, String phone, int income, String employer) {
        return new Tenant(id("tenant:" + key), org.id(), first, last,
                (first + "." + last).toLowerCase() + "@example.com", phone, income, employer);
    }

    private static Lease lease(Property p, String unitNo, String tenantKey, LocalDate today, int startMonthsAgo, int rent, String profile) {
        Unit u = unit(propertyKey(p), unitNo);
        LocalDate start = today.plusMonths(startMonthsAgo).withDayOfMonth(1);
        return new Lease(id("lease:" + propertyKey(p) + ":" + unitNo), p.orgId(), u.id(), p.id(), p.ownerId(), tenant(tenantKey).id(),
                start, start.plusMonths(12).minusDays(1), bd(rent), bd(rent), profile);
    }

    private static Vendor vendor(String key, String name, String contact, List<String> trades, List<String> cities, double rating,
                                 int avgCost, String phone, int jobs) {
        return new Vendor(id("vendor:" + key), SUMMIT.id(), name, contact, trades, cities, rating, bd(avgCost), phone,
                contact.toLowerCase().replace(' ', '.') + "@" + name.toLowerCase().replaceAll("[^a-z]", "") + ".com", jobs);
    }
}
