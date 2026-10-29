package com.propertyos.leasing.service;

import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/** Public (signed-out) access: published listings, enquiries, showing requests and applications. Runs as the system with explicit filters. */
@Service
public class PublicService {
    private final Db db;
    private final EventPublisher events;
    private final LeadService leads;
    private final TransactionTemplate tx;
    private final com.propertyos.common.security.SignedLinks links;

    public PublicService(Db db, EventPublisher events, LeadService leads, PlatformTransactionManager tm, com.propertyos.common.security.SignedLinks links) {
        this.links = links;
        this.tx = new TransactionTemplate(tm);
        this.db = db;
        this.events = events;
        this.leads = leads;
    }

    private <T> T asSystem(java.util.function.Supplier<T> work) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(st -> work.get()));
    }

    /**
     * Public: lists live listings matching the filters. Runs as the system because visitors are not signed in.
     *
     * @param city    city filter, or null
     * @param beds    minimum bedrooms, or null
     * @param maxRent maximum monthly rent, or null
     */
    public List<Map<String, Object>> listings(String city, Integer beds, Integer maxRent) {
        return asSystem(() -> queryListings(city, beds, maxRent));
    }

    List<Map<String, Object>> queryListings(String city, Integer beds, Integer maxRent) {
        StringBuilder sql = new StringBuilder("""
                select l.id, l.title, l.description, l.rent, l.available_date, l.photos, l.highlights,
                       p.name as property_name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.amenities,
                       u.beds, u.baths, u.sqft
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                where l.status = 'LIVE'""");
        List<Object> args = new ArrayList<>();
        if (city != null && !city.isBlank()) {
            sql.append(" and lower(p.city) like ?");
            args.add("%" + city.toLowerCase() + "%");
        }
        if (beds != null) {
            sql.append(" and u.beds >= ?");
            args.add(beds);
        }
        if (maxRent != null) {
            sql.append(" and l.rent <= ?");
            args.add(maxRent);
        }
        sql.append(" order by l.published_at desc nulls last");
        return db.list(sql.toString(), args.toArray());
    }

    /**
     * Public: returns one live listing.
     *
     * @throws ApiException (404) when it is not live
     */
    public Map<String, Object> listing(UUID id) {
        return asSystem(() -> {
            Map<String, Object> l = findLive(id);
            db.update("update leasing.listing set views = views + 1 where id = ?", id);
            return l;
        });
    }

    Map<String, Object> findLive(UUID id) {
        return db.one("Listing", """
                select l.id, l.title, l.description, l.rent, l.deposit, l.available_date, l.photos, l.highlights,
                       p.name as property_name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.amenities, u.beds, u.baths, u.sqft
                from leasing.listing l join leasing.property p on p.id = l.property_id join leasing.unit u on u.id = l.unit_id
                where l.id = ? and l.status = 'LIVE'""", id);
    }

    /**
     * Public: records an enquiry from the website as a lead.
     */
    public Map<String, Object> enquire(UUID listingId, Map<String, Object> body) {
        return asSystem(() -> leads.enquire(listingId, body, "WEBSITE"));
    }

    /**
     * Public: books a showing requested from the website.
     */
    public Map<String, Object> requestShowing(UUID listingId, Map<String, Object> body) {
        return asSystem(() -> leads.book(listingId, body, true));
    }

    /**
     * Public: stores a rental application and returns a signed token the applicant uses to upload documents.
     */
    public Map<String, Object> apply(UUID listingId, Map<String, Object> b) {
        return asSystem(() -> doApply(listingId, b));
    }

    Map<String, Object> doApply(UUID listingId, Map<String, Object> b) {
        Map<String, Object> li = db.one("Listing", """
                select l.*, u.number, p.name as pname from leasing.listing l join leasing.unit u on u.id = l.unit_id
                join leasing.property p on p.id = l.property_id where l.id = ? and l.status = 'LIVE'""", listingId);
        String first = Req.required(b, "firstName");
        String last = Req.required(b, "lastName");
        String email = Req.email(b, "email");
        if (!Boolean.TRUE.equals(b.get("consent")))
            throw ApiException.badRequest("Consent to a credit and background check is required to apply.");
        UUID orgId = (UUID) li.get("org_id");
        Optional<Map<String, Object>> dup = db.find("""
                select id from leasing.application where listing_id = ? and lower(applicant_email) = lower(?) and status not in ('REJECTED','WITHDRAWN')""", listingId, email);
        if (dup.isPresent()) throw ApiException.conflict("You've already applied for this home.");
        UUID personId = db.find("select id from leasing.person where org_id = ? and lower(email) = lower(?)", orgId, email)
                .map(m -> (UUID) m.get("id")).orElse(null);
        if (personId == null) {
            personId = UUID.randomUUID();
            db.update("insert into leasing.person (id, org_id, first_name, last_name, email, phone, annual_income, employer, months_employed) values (?,?,?,?,?,?,?,?,?)",
                    personId, orgId, first, last, email, Req.str(b, "phone"),
                    Optional.ofNullable(Req.money(b, "monthlyIncome")).map(m -> m.multiply(java.math.BigDecimal.valueOf(12))).orElse(null),
                    Req.str(b, "employer"), Req.integer(b, "monthsEmployed"));
        }
        UUID id = UUID.randomUUID();
        db.update("""
                insert into leasing.application (id, org_id, listing_id, unit_id, property_id, owner_id, applicant_id, applicant_name, applicant_email, unit_label,
                    status, monthly_income, employer, months_employed, current_rent, landlord_reference, occupants, pets, move_in_date, documents, fee_paid)
                values (?,?,?,?,?,?,?,?,?,?,'SUBMITTED',?,?,?,?,?,?,?,?,?::jsonb,true)""",
                id, orgId, listingId, li.get("unit_id"), li.get("property_id"), li.get("owner_id"), personId, first + " " + last, email,
                li.get("pname") + " " + li.get("number"), Req.money(b, "monthlyIncome"), Req.str(b, "employer"), Req.integer(b, "monthsEmployed"),
                Req.money(b, "currentRent"), Req.str(b, "landlordReference"), Optional.ofNullable(Req.integer(b, "occupants")).orElse(1),
                Req.str(b, "pets"), Req.date(b, "moveInDate"), db.json(Req.strings(b, "documents")));
        events.publish(orgId, "ApplicationSubmitted", "Application", id,
                Map.of("listingId", listingId.toString(), "applicantName", first + " " + last, "applicantEmail", email, "unit", li.get("number").toString()));
        // A signed link lets the applicant (who has no account) add documents to this one application.
        return Map.of("id", id, "status", "SUBMITTED", "uploadToken", links.sign("application-upload", id),
                "message", "Application received. Upload your documents now so screening isn't held up. We'll email you as soon as screening is complete.");
    }
}
