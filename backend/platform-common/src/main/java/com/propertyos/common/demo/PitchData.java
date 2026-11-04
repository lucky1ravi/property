package com.propertyos.common.demo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The pitch environment: a ready-made story for showing PropertyOS to someone. Three companies, one on each plan (Cedar Grove on Starter, Harborview on
 * Growth, Summit Ridge on Enterprise), each with a new building, listings with photos, and resident ratings and reviews of different quality. Summit Ridge also
 * has an active listing boost, two marketing campaigns whose leads sit at different stages, a lead that is ready to become an application, and a new resident
 * with open move-in charges.
 *
 * <p>This is only a description of the data, like {@link DemoData}. Every id is made from a name, so each service can seed its own tables from it and a second
 * run finds everything already there. It is only ever used by the seeders, and only when demo mode and the pitch switch are both on ({@link #enabled}).</p>
 */
public final class PitchData {
    private PitchData() {
    }

    /** The one rule for whether pitch data may be written: demo mode must be on, and the pitch switch must be on. */
    public static boolean enabled(boolean demo, boolean pitch) {
        return demo && pitch;
    }

    static UUID id(String key) {
        return DemoData.id("pitch:" + key);
    }

    private static BigDecimal bd(double v) {
        return BigDecimal.valueOf(v).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    // ------------------------------------------------------------------------------------------------------ companies

    /** The Starter-plan company. */
    public static final DemoData.Org CEDAR = new DemoData.Org(id("org:cedar"), "Cedar Grove Rentals", "Cedar Grove Rentals LLC", "TX", "STARTER");
    public static final DemoData.Owner DOLORES = new DemoData.Owner(id("owner:dolores"), CEDAR.id(), "Dolores Kim", "Dolores Kim", "dolores.kim@example.com", "(512) 555-0161", bd(400), bd(9), "7305");

    /** The companies the pitch adds data to: the new Starter company and the two demo companies that already exist. */
    public static List<UUID> orgIds() {
        return List.of(CEDAR.id(), DemoData.HARBOR.id(), DemoData.SUMMIT.id());
    }

    // ------------------------------------------------------------------------------------------------------ buildings and units

    public record Building(String key, DemoData.Property property, List<DemoData.Unit> units) {
        public UUID orgId() {
            return property.orgId();
        }
    }

    private static DemoData.Unit unit(UUID property, String key, String number, int beds, double baths, int sqft, int rent) {
        return new DemoData.Unit(id("unit:" + key + ":" + number), property, number, beds, baths, sqft, bd(rent));
    }

    private static Building building(String key, DemoData.Org org, DemoData.Owner owner, String name, String street, String city, String state, String zip, double lat, double lng,
                                     String type, int year, List<String> amenities, String photo, int[][] units) {
        DemoData.Property p = new DemoData.Property(id("property:" + key), org.id(), name, street, city, state, zip, lat, lng, type, owner.id(), year, amenities, "", photo);
        List<DemoData.Unit> us = new ArrayList<>();
        for (int[] u : units) us.add(unit(p.id(), key, String.valueOf(u[0]), u[1], u[2] / 10.0, u[3], u[4]));
        return new Building(key, p, List.copyOf(us));
    }

    /** Enterprise: a new Austin building for Summit Ridge, with a resident moving in, a boosted listing and the campaigns' homes. */
    public static final Building RIVERSIDE = building("riverside", DemoData.SUMMIT, DemoData.JAMES, "Riverside Commons", "2400 E Riverside Dr", "Austin", "TX", "78741",
            30.2341, -97.7295, "MULTIFAMILY", 2019, List.of("Rooftop terrace", "Pool", "Package lockers", "Bike storage", "EV charging"),
            "https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?w=1200&q=80",
            new int[][]{{101, 1, 10, 690, 1725}, {102, 1, 10, 720, 1795}, {103, 2, 20, 1010, 2250}, {104, 2, 20, 1040, 2310}, {105, 0, 10, 480, 1480}, {106, 2, 20, 1060, 2380}});
    /** Growth: a new San Francisco building for Harborview. */
    public static final Building BAYLINE = building("bayline", DemoData.HARBOR, DemoData.HARBOR_OWNER, "Bayline Flats", "1450 Valencia St", "San Francisco", "CA", "94110",
            37.7469, -122.4204, "MULTIFAMILY", 2014, List.of("Roof deck", "In-unit laundry", "Bike room"),
            "https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?w=1200&q=80",
            new int[][]{{2, 1, 10, 650, 3150}, {3, 2, 20, 940, 4050}, {4, 1, 10, 670, 3250}, {5, 0, 10, 440, 2650}});
    /** Starter: a small Texas company's first building. */
    public static final Building CEDAR_COTTAGES = building("cedar", CEDAR, DOLORES, "Cedar Grove Cottages", "810 Cedar Ln", "Round Rock", "TX", "78664",
            30.5083, -97.6789, "TOWNHOME", 2008, List.of("Fenced yard", "Garage", "Pet friendly"),
            "https://images.unsplash.com/photo-1568605114967-8130f3a36994?w=1200&q=80",
            new int[][]{{1, 2, 15, 1050, 1650}, {2, 3, 20, 1320, 1950}, {3, 2, 15, 1080, 1700}});

    public static List<Building> buildings() {
        return List.of(CEDAR_COTTAGES, BAYLINE, RIVERSIDE);
    }

    public static List<Building> buildings(UUID org) {
        return buildings().stream().filter(b -> b.orgId().equals(org)).toList();
    }

    // ------------------------------------------------------------------------------------------------------ listings

    private static final List<String> PHOTOS = List.of(
            "https://images.unsplash.com/photo-1522708323590-d24dbb6b0267?w=1200&q=80", "https://images.unsplash.com/photo-1560448204-e02f11c3d0e2?w=1200&q=80",
            "https://images.unsplash.com/photo-1556909114-f6e7ad7d3136?w=1200&q=80", "https://images.unsplash.com/photo-1505691938895-1758d7feb511?w=1200&q=80",
            "https://images.unsplash.com/photo-1484154218962-a197022b5858?w=1200&q=80", "https://images.unsplash.com/photo-1493809842364-78817add7ffb?w=1200&q=80",
            "https://images.unsplash.com/photo-1545324418-cc1a3fa10c00?w=1200&q=80", "https://images.unsplash.com/photo-1502672260266-1c1ef2d93688?w=1200&q=80",
            "https://images.unsplash.com/photo-1512917774080-9991f1c4c750?w=1200&q=80", "https://images.unsplash.com/photo-1494526585095-c41746248156?w=1200&q=80");

    public record Listing(String key, UUID id, Building building, DemoData.Unit unit, String status, String title, String description, List<String> photos,
                          List<String> highlights, int liveDaysAgo, int views, int enquiries) {
        public UUID orgId() {
            return building.orgId();
        }

        public DemoData.Property property() {
            return building.property();
        }
    }

    private static List<String> photos(int start, int count) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < count; i++) out.add(PHOTOS.get((start + i * 3) % PHOTOS.size()));
        return out;
    }

    private static Listing listing(Building b, String number, String status, int detail, int daysAgo, int views, int enquiries) {
        DemoData.Unit u = b.units().stream().filter(x -> x.number().equals(number)).findFirst().orElseThrow();
        DemoData.Property p = b.property();
        String kind = u.beds() == 0 ? "studio" : u.beds() + "-bedroom";
        String title = (u.beds() == 0 ? "Studio" : u.beds() + "-bedroom") + " at " + p.name() + " · " + p.city() + ", " + p.state();
        String a = String.join(", ", p.amenities().subList(0, Math.min(3, p.amenities().size())));
        // detail 3: a full, well-written listing. 2: a good one. 1: a thin one, so the ranking panel has something to suggest.
        String text = switch (detail) {
            case 3 -> "Unit " + number + " is a bright " + u.sqft() + " sq ft " + kind + " with " + u.baths() + " bath" + (u.baths() == 1 ? "" : "s") + " on a quiet floor of "
                    + p.name() + " in " + p.city() + ". It has a modern kitchen with stainless appliances, wide-plank flooring, generous closets and big windows that fill the home with light. "
                    + "The building offers " + a + ", and you are minutes from restaurants, parks and transit. Pets are welcome, and move-in is simple: sign online and we hand you the keys.";
            case 2 -> "Unit " + number + " is a " + u.sqft() + " sq ft " + kind + " with " + u.baths() + " bath" + (u.baths() == 1 ? "" : "s") + " at " + p.name() + " in " + p.city()
                    + ". Updated kitchen, good storage and building amenities including " + a + ". Close to shops and transit.";
            default -> "A " + kind + " home in " + p.city() + ", " + u.sqft() + " sq ft. Available soon.";
        };
        List<String> highlights = detail >= 2 ? p.amenities().subList(0, Math.min(3, p.amenities().size())) : List.of();
        int count = detail == 3 ? 5 : detail == 2 ? 4 : 2;
        return new Listing(b.key() + ":" + number, id("listing:" + b.key() + ":" + number), b, u, status, title, text, photos(Math.abs((b.key() + number).hashCode()) % 7, count), highlights, daysAgo, views, enquiries);
    }

    private static final List<Listing> LISTINGS = List.of(
            listing(RIVERSIDE, "101", "LEASED", 3, 40, 210, 14), listing(RIVERSIDE, "102", "LIVE", 3, 18, 392, 21), listing(RIVERSIDE, "103", "LIVE", 3, 16, 244, 12),
            listing(RIVERSIDE, "104", "LIVE", 3, 12, 188, 9), listing(RIVERSIDE, "105", "LIVE", 2, 7, 96, 5), listing(RIVERSIDE, "106", "DRAFT", 3, 0, 0, 0),
            listing(BAYLINE, "2", "LIVE", 2, 21, 233, 11), listing(BAYLINE, "3", "LIVE", 2, 15, 171, 8), listing(BAYLINE, "4", "LIVE", 2, 9, 120, 6), listing(BAYLINE, "5", "LIVE", 2, 4, 61, 3),
            listing(CEDAR_COTTAGES, "1", "LIVE", 1, 11, 74, 4), listing(CEDAR_COTTAGES, "2", "LIVE", 1, 8, 51, 3), listing(CEDAR_COTTAGES, "3", "LIVE", 1, 5, 32, 1));

    public static List<Listing> listings() {
        return LISTINGS;
    }

    public static Listing listing(String key) {
        return LISTINGS.stream().filter(l -> l.key().equals(key)).findFirst().orElseThrow(() -> new IllegalArgumentException(key));
    }

    // ------------------------------------------------------------------------------------------------------ people

    /** The resident who is moving in at Riverside Commons 101. */
    public static final UUID JAMIE_ID = id("tenant:jamie");
    public static final String JAMIE_EMAIL = "jamie.rivera@example.com";

    /** People who can sign in. Each has the demo password. */
    public static List<DemoData.User> users() {
        return List.of(
                new DemoData.User(id("user:dolores"), CEDAR.id(), "Dolores Kim", "dolores.kim@cedargrovehomes.com", "Owner and administrator", "ORG_ADMIN", null, List.of(), "Company owner / admin (Starter)"),
                new DemoData.User(id("user:ben"), CEDAR.id(), "Ben Ortiz", "ben.ortiz@cedargrovehomes.com", "Leasing Agent", "LEASING_AGENT", null, List.of(CEDAR_COTTAGES.property().id()), "Leasing agent (Starter)"),
                new DemoData.User(id("user:tom"), DemoData.HARBOR.id(), "Tom Nakamura", "tom.nakamura@harborviewres.com", "Broker, Bayline Flats", "BROKER", null, List.of(BAYLINE.property().id()), "Broker (Growth)"),
                new DemoData.User(id("user:jamie"), DemoData.SUMMIT.id(), "Jamie Rivera", JAMIE_EMAIL, "Resident, Riverside Commons 101", "TENANT", JAMIE_ID, List.of(), "Resident moving in (Enterprise)"));
    }

    // ------------------------------------------------------------------------------------------------------ ratings and reviews

    /**
     * One resident rating.
     *
     * @param aiScore the comment's moderation score; a score under 70 keeps the comment off the public page (the stars still count)
     * @param reply   the company's public reply, or null
     */
    public record Review(UUID id, UUID orgId, String subjectType, UUID subjectId, UUID propertyId, UUID unitId, UUID ownerId, UUID tenantId, UUID raterUserId, String raterName,
                         int stars, String comment, int daysAgo, Integer aiScore, String aiReasons, String reply, String replyBy, Integer replyDaysAgo) {
    }

    private static final List<String> NAMES = List.of("Avery Collins", "Brooke Sandoval", "Caleb Whitaker", "Dana Okafor", "Eli Marchetti", "Farah Qureshi", "Gideon Park", "Hana Lindgren",
            "Ian Kowalski", "Jules Bernard", "Kira Tanaka", "Lucas Ferreira", "Mina Haddad", "Nolan Pierce", "Odette Laurent", "Preston Obi", "Quinn Abbott", "Rosa Delgado",
            "Silas Moreno", "Tamsin Reyes", "Uma Nair", "Vince Carmichael", "Willa Brandt", "Xavier Ibarra", "Yara Costa", "Zane Holloway", "Annika Berg", "Bruno Salazar");

    private static final List<String> FIVE = List.of(
            "Moved in last spring and it has been great. Repairs are done the same day and the staff actually answer the phone.",
            "Quiet building, lots of light, and the common areas are always spotless. I renewed without a second thought.",
            "The leasing process was painless and the apartment was spotless on move-in day. Highly recommend.",
            "Love the location. Walkable to everything, and the management team is responsive and kind.",
            "Best rental experience I have had. Packages never go missing and maintenance texts you before they arrive.",
            "Great value for the neighbourhood. The amenities are real, not just a photo on a website.");
    private static final List<String> FOUR = List.of(
            "Solid place for the price. Parking can be tight on weekends, but otherwise no complaints.",
            "Maintenance is quick and polite. Water pressure in the shower could be better.",
            "Good neighbours and a clean building. The elevator was out for a week once, and they kept us updated.");
    private static final List<String> THREE = List.of(
            "It is fine. Some street noise at night, and the dishwasher needed two visits to fix, but they did fix it.",
            "Decent value, but the laundry room is always busy in the evenings.");
    private static final String HIDDEN_COMMENT = "Worst landlord ever, the manager is an idiot. Call me on 512-555-0199 if you want the real story.";

    private static final List<String> REPLY_THANKS = List.of("Thank you for the kind words. We are so glad you are settled in.", "That means a lot to our team. Thank you for living with us.",
            "Thanks for taking the time to write this. We will pass it on to the whole team.");
    private static final List<String> REPLY_SORRY = List.of("Thank you for the honest feedback. We have added a second evening laundry window and an extra package shelf since.",
            "We are sorry it took two visits. The dishwasher is now on our quarterly check list. Please tell us if anything else comes up.");

    private static void add(List<Review> out, Building b, DemoData.Unit unit, String tag, int[] stars, boolean hiddenLast, String replier) {
        UUID org = b.orgId(), prop = b.property().id();
        String subjectType = unit == null ? "PROPERTY" : "UNIT";
        UUID subject = unit == null ? prop : unit.id();
        for (int i = 0; i <= stars.length; i++) {
            boolean hidden = i == stars.length;
            if (hidden && !hiddenLast) break;
            int s = hidden ? 1 : stars[i];
            List<String> pool = s >= 5 ? FIVE : s == 4 ? FOUR : THREE;
            String comment = hidden ? HIDDEN_COMMENT : pool.get((i + tag.length()) % pool.size());
            String name = NAMES.get((i * 5 + tag.length() * 3 + (unit == null ? 0 : 11)) % NAMES.size());   // 5 and 28 share no factor, so the names do not repeat within a building
            boolean replied = !hidden && (s <= 3 || i % 4 == 1);
            String reply = !replied ? null : s <= 3 ? REPLY_SORRY.get(i % REPLY_SORRY.size()) : REPLY_THANKS.get(i % REPLY_THANKS.size());
            out.add(new Review(id("review:" + b.key() + ":" + tag + ":" + i), org, subjectType, subject, prop, unit == null ? null : unit.id(), b.property().ownerId(),
                    id("resident:" + b.key() + ":" + tag + ":" + i), id("rater:" + b.key() + ":" + tag + ":" + i), name, s, comment, 8 + (i * 19 + tag.length() * 5) % 330,
                    hidden ? Integer.valueOf(22) : Integer.valueOf(88 + (i * 3) % 11), hidden ? "Insults the manager and shares a personal phone number." : "Civil, specific and about the home.",
                    reply, replied ? replier : null, replied ? Integer.valueOf(Math.max(1, (8 + (i * 19 + tag.length() * 5) % 330) - 2)) : null));
        }
    }

    private static final List<Review> REVIEWS;

    static {
        List<Review> r = new ArrayList<>();
        // Riverside Commons: loved. 14 five-star, 3 four-star and a 3 are shown; one more rating (a 1-star with an abusive comment) is counted but its comment is hidden by moderation.
        add(r, RIVERSIDE, null, "all", new int[]{5, 5, 4, 5, 5, 5, 3, 5, 5, 4, 5, 5, 5, 5, 4, 5, 5, 5}, true, "Marcus Johnson");
        add(r, RIVERSIDE, RIVERSIDE.units().get(1), "102", new int[]{5, 5, 4}, false, "Marcus Johnson");
        add(r, RIVERSIDE, RIVERSIDE.units().get(2), "103", new int[]{4, 4}, false, "Marcus Johnson");
        add(r, RIVERSIDE, RIVERSIDE.units().get(3), "104", new int[]{5}, false, "Marcus Johnson");
        // Bayline Flats: good but not perfect.
        add(r, BAYLINE, null, "all", new int[]{5, 4, 4, 5, 4, 3, 4}, false, "Kevin Park");
        add(r, BAYLINE, BAYLINE.units().get(0), "2", new int[]{4, 5}, false, "Kevin Park");
        add(r, BAYLINE, BAYLINE.units().get(3), "5", new int[]{3}, false, "Kevin Park");
        // Cedar Grove Cottages: new, few ratings.
        add(r, CEDAR_COTTAGES, null, "all", new int[]{4, 5, 4, 3}, false, "Dolores Kim");
        add(r, CEDAR_COTTAGES, CEDAR_COTTAGES.units().get(0), "1", new int[]{5}, false, "Dolores Kim");
        REVIEWS = List.copyOf(r);
    }

    public static List<Review> reviews() {
        return REVIEWS;
    }

    public static List<Review> reviews(UUID org) {
        return REVIEWS.stream().filter(x -> x.orgId().equals(org)).toList();
    }

    // ------------------------------------------------------------------------------------------------------ campaigns and leads (Summit Ridge)

    /** The secret is fixed and written in docs/LOCAL-RUN.md so a presenter can post a signed lead live. It is demo data and must never be used anywhere real. */
    public record Campaign(UUID id, UUID orgId, String name, String channel, BigDecimal spend, int startedDaysAgo, int endsInDays, String publicKey, String secret, String trackingCode) {
    }

    public static final Campaign FACEBOOK = new Campaign(id("campaign:facebook"), DemoData.SUMMIT.id(), "Riverside launch · Facebook ads", "FACEBOOK_ADS", bd(1850), 35, 25,
            "cmp_pitch_facebook", "whsec_pitch_facebook_demo", "fbriver26");
    public static final Campaign ZILLOW = new Campaign(id("campaign:zillow"), DemoData.SUMMIT.id(), "Zillow featured homes · Riverside", "ZILLOW", bd(2400), 28, 32,
            "cmp_pitch_zillow", "whsec_pitch_zillow_demo", "zlriver26");

    public static List<Campaign> campaigns() {
        return List.of(FACEBOOK, ZILLOW);
    }

    /**
     * A lead at some stage. {@code status} is NEW or RESPONDED; later stages are the showing, the application and the lease that belong to the same person.
     */
    public record Lead(UUID id, Campaign campaign, Listing listing, String name, String email, String phone, String message, String status, int score, String intent, int daysAgo) {
        public String firstName() {
            return name.split(" ")[0];
        }
    }

    private static Lead lead(Campaign c, String listing, String name, String status, int score, String intent, int daysAgo, String message) {
        String email = name.toLowerCase().replace(' ', '.') + "@example.com";
        String phone = "(512) 555-01" + String.format("%02d", Math.abs(name.hashCode()) % 100);
        return new Lead(id("lead:" + c.trackingCode() + ":" + email), c, listing(listing), name, email, phone, message, status, score, intent, daysAgo);
    }

    private static final List<Lead> LEADS = List.of(
            // Facebook ads: 12 people. Tessa has toured and is ready to apply. Three went on to apply, and one of those is now a resident.
            lead(FACEBOOK, "riverside:103", "Tessa Nguyen", "RESPONDED", 94, "APPLY", 3, "I toured yesterday and love the 2-bedroom. How do I apply? I would like to move in on the 1st."),
            lead(FACEBOOK, "riverside:102", "Omar Haddad", "NEW", 71, "BOOK_SHOWING", 1, "Is the 1-bedroom still available? Could I see it this weekend?"),
            lead(FACEBOOK, "riverside:104", "Lila Brandt", "NEW", 58, "QUESTION", 2, "Do you allow cats, and is there a pet deposit?"),
            lead(FACEBOOK, "riverside:103", "Devon Price", "NEW", 66, "BOOK_SHOWING", 4, "Saw your ad. Can I book a tour on Thursday evening?"),
            lead(FACEBOOK, "riverside:102", "Sofia Marin", "RESPONDED", 77, "BOOK_SHOWING", 8, "Is parking included? I work from home and need a quiet unit."),
            lead(FACEBOOK, "riverside:105", "Gabe Ellison", "RESPONDED", 49, "QUESTION", 12, "What is the lease length, and can it be six months?"),
            lead(FACEBOOK, "riverside:102", "Hannah Voss", "RESPONDED", 83, "BOOK_SHOWING", 15, "We are relocating from Denver. Can we tour tomorrow afternoon?"),
            lead(FACEBOOK, "riverside:104", "Raj Mehta", "NEW", 62, "QUESTION", 21, "Does the rent include water and trash?"),
            lead(FACEBOOK, "riverside:105", "Chloe Dufour", "RESPONDED", 55, "QUESTION", 26, "Is the studio quiet? I am a light sleeper."),
            lead(FACEBOOK, "riverside:101", "Jamie Rivera", "RESPONDED", 91, "APPLY", 34, "I want to apply for the 1-bedroom. I can move in next month."),
            lead(FACEBOOK, "riverside:103", "Wei Zhang", "RESPONDED", 86, "APPLY", 24, "My partner and I would like to apply for the 2-bedroom."),
            lead(FACEBOOK, "riverside:104", "Alicia Romero", "RESPONDED", 79, "APPLY", 18, "Is the 2-bedroom on the second floor still free? We would like to apply."),
            // Zillow: 7 people, one application waiting for documents.
            lead(ZILLOW, "riverside:102", "Imani Clarke", "NEW", 74, "BOOK_SHOWING", 2, "I found this on Zillow. Can I tour it on Saturday morning?"),
            lead(ZILLOW, "riverside:103", "Pedro Santos", "NEW", 60, "QUESTION", 5, "What are the move-in costs for the 2-bedroom?"),
            lead(ZILLOW, "riverside:104", "Yuki Tanaka", "RESPONDED", 81, "BOOK_SHOWING", 7, "Could we see the 2-bedroom with the best light? We both work from home."),
            lead(ZILLOW, "riverside:105", "Elliot Grant", "RESPONDED", 52, "QUESTION", 11, "Is there a gym in the building?"),
            lead(ZILLOW, "riverside:103", "Mara Lindqvist", "RESPONDED", 68, "QUESTION", 14, "Is the 2-bedroom available before the 15th?"),
            lead(ZILLOW, "riverside:102", "Dmitri Volkov", "NEW", 45, "QUESTION", 19, "Any discount for a 15-month lease?"),
            lead(ZILLOW, "riverside:102", "Bea Okonkwo", "RESPONDED", 88, "APPLY", 22, "I would like to apply for the 1-bedroom. My documents are ready to send."));

    public static List<Lead> leads() {
        return LEADS;
    }

    public static Lead lead(String name) {
        return LEADS.stream().filter(l -> l.name().equals(name)).findFirst().orElseThrow(() -> new IllegalArgumentException(name));
    }

    /** The lead who is ready to become an application: she has toured and asked how to apply. */
    public static final String READY_TO_APPLY = "Tessa Nguyen";

    /** A tour. {@code dayOffset} is negative for the past. */
    public record Showing(UUID id, Lead lead, String status, int dayOffset, String notes) {
    }

    public static List<Showing> showings() {
        return List.of(
                new Showing(id("showing:tessa"), lead("Tessa Nguyen"), "ATTENDED", -1, "Loved the kitchen and the light. Asked how to apply and when she could move in."),
                new Showing(id("showing:sofia"), lead("Sofia Marin"), "ATTENDED", -6, "Liked the quiet side of the building. Wants to think about parking."),
                new Showing(id("showing:hannah"), lead("Hannah Voss"), "CONFIRMED", 1, "Relocating from Denver. Tour booked."),
                new Showing(id("showing:omar"), lead("Omar Haddad"), "REQUESTED", 2, "Asked for Saturday morning."));
    }

    /** An application and who made it. {@code status} is a stage of the application workflow. */
    public record Application(UUID id, UUID personId, Lead lead, String status, int annualIncome, Integer creditScore, String employer, int daysAgo) {
        public String name() {
            return lead.name();
        }
    }

    public static List<Application> applications() {
        return List.of(
                new Application(id("application:jamie"), JAMIE_ID, lead("Jamie Rivera"), "APPROVED", 78000, 731, "Austin Health Partners", 30),
                new Application(id("application:wei"), id("applicant:wei"), lead("Wei Zhang"), "SCREENED", 92000, 758, "Dell Technologies", 20),
                new Application(id("application:alicia"), id("applicant:alicia"), lead("Alicia Romero"), "OWNER_REVIEW", 66000, 689, "Texas Children's Hospital", 14),
                new Application(id("application:bea"), id("applicant:bea"), lead("Bea Okonkwo"), "DOCS_PENDING", 84000, null, "Whole Foods Market", 18));
    }

    /** Jamie's lease, from the approved application. It starts a few days from now, so the move-in charges are open. */
    public static final String LEASE_LISTING = "riverside:101";
    public static final int LEASE_STARTS_IN_DAYS = 4;
    public static final UUID LEASE_ID = id("lease:riverside:101");

    // ------------------------------------------------------------------------------------------------------ boost (Summit Ridge)

    /** A paid boost. The invoice is a platform invoice (identity service) and is already paid. */
    public record Boost(UUID id, String listingKey, int days, BigDecimal amount, int startedDaysAgo, String status, UUID invoiceId, String invoiceNumber) {
    }

    /** Running now: started yesterday, 7 days. Re-running the pitch moves its window so it is still running. */
    public static final Boost ACTIVE_BOOST = new Boost(id("boost:current"), "riverside:102", 7, bd(49), 1, "ACTIVE", id("invoice:boost-current"), "PO-PITCH-0001");
    /** A finished one, to show history. */
    public static final Boost PAST_BOOST = new Boost(id("boost:past"), "riverside:102", 14, bd(89), 45, "EXPIRED", id("invoice:boost-past"), "PO-PITCH-0002");

    public static List<Boost> boosts() {
        return List.of(PAST_BOOST, ACTIVE_BOOST);
    }

    // ------------------------------------------------------------------------------------------------------ sign-in guide (used by the documentation and the log)

    /** A line for the sign-in table: who, which company and plan, what to show. */
    public static List<Map<String, String>> signIns() {
        return List.of(
                Map.of("email", "sarah.mitchell@summitridgepg.com", "company", "Summit Ridge Property Group (Enterprise)", "role", "Company administrator"),
                Map.of("email", "marcus.johnson@summitridgepg.com", "company", "Summit Ridge Property Group (Enterprise)", "role", "Broker"),
                Map.of("email", JAMIE_EMAIL, "company", "Summit Ridge Property Group (Enterprise)", "role", "Resident with open move-in charges"),
                Map.of("email", "kevin.park@harborviewres.com", "company", "Harborview Residential (Growth)", "role", "Company administrator"),
                Map.of("email", "tom.nakamura@harborviewres.com", "company", "Harborview Residential (Growth)", "role", "Broker"),
                Map.of("email", "dolores.kim@cedargrovehomes.com", "company", "Cedar Grove Rentals (Starter)", "role", "Company administrator"),
                Map.of("email", "ben.ortiz@cedargrovehomes.com", "company", "Cedar Grove Rentals (Starter)", "role", "Leasing agent"),
                Map.of("email", "alex.morgan@propertyos.io", "company", "PropertyOS Platform", "role", "Platform administrator"));
    }
}
