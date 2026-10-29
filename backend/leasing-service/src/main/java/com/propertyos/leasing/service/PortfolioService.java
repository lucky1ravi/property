package com.propertyos.leasing.service;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** Properties, units and owners. */
@Service
public class PortfolioService {
    private final Db db;
    private final AuditService audit;

    public PortfolioService(Db db, AuditService audit) {
        this.db = db;
        this.audit = audit;
    }

    /**
     * Lists properties with unit and occupancy counts.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> properties() {
        return db.list("""
                select p.id, p.name, p.street, p.city, p.state, p.zip, p.lat, p.lng, p.type, p.year_built, p.amenities, p.photo_url, p.photos,
                       p.owner_id, o.name as owner_name,
                       count(u.id) as units,
                       count(u.id) filter (where u.status = 'OCCUPIED') as occupied,
                       count(u.id) filter (where u.status = 'VACANT') as vacant,
                       coalesce(sum(u.current_rent) filter (where u.status = 'OCCUPIED'), 0) as rent_roll,
                       coalesce(sum(u.market_rent), 0) as market_rent_total,
                       (select count(*) from leasing.listing l where l.property_id = p.id and l.status = 'LIVE') as live_listings
                from leasing.property p
                left join leasing.owner o on o.id = p.owner_id
                left join leasing.unit u on u.property_id = p.id
                group by p.id, o.name order by p.name""");
    }

    /**
     * Returns one property with its owner and units.
     *
     * @throws ApiException (404) when not found
     */
    @Transactional(readOnly = true)
    public Map<String, Object> property(UUID id) {
        Map<String, Object> p = new LinkedHashMap<>(db.one("Property", """
                select p.*, o.name as owner_name, o.email as owner_email, o.phone as owner_phone
                from leasing.property p left join leasing.owner o on o.id = p.owner_id where p.id = ?""", id));
        p.put("units", db.list("""
                select u.*, l.id as lease_id, l.tenant_name, l.end_date as lease_end, l.status as lease_status,
                       (select li.id from leasing.listing li where li.unit_id = u.id and li.status <> 'LEASED' order by li.created_at desc limit 1) as listing_id,
                       (select li.status from leasing.listing li where li.unit_id = u.id and li.status <> 'LEASED' order by li.created_at desc limit 1) as listing_status
                from leasing.unit u
                left join leasing.lease l on l.unit_id = u.id and l.status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN')
                where u.property_id = ? order by u.number""", id));
        return p;
    }

    /**
     * Lists units with their property.
     *
     * @param status unit status to filter by, or null for all
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> units(String status) {
        return status == null
                ? db.list("select u.*, p.name as property_name, p.city, p.state from leasing.unit u join leasing.property p on p.id = u.property_id order by p.name, u.number")
                : db.list("select u.*, p.name as property_name, p.city, p.state from leasing.unit u join leasing.property p on p.id = u.property_id where u.status = ? order by p.name, u.number", status);
    }

    /**
     * Lists owners with property counts and fee terms.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> owners() {
        return db.list("""
                select o.id, o.name, o.contact, o.email, o.phone, o.approval_limit, o.management_fee_pct, o.bank_last4,
                       (select count(*) from leasing.property p where p.owner_id = o.id) as properties,
                       (select count(*) from leasing.unit u where u.owner_id = o.id) as units
                from leasing.owner o order by o.name""").stream().map(this::maskOwner).toList();
    }

    private Map<String, Object> maskOwner(Map<String, Object> o) {
        Map<String, Object> m = new LinkedHashMap<>(o);
        String access = UserContextHolder.require().fieldAccess("Owner.bankAccount");
        if ("HIDDEN".equals(access)) m.remove("bank_last4");
        else if ("MASKED".equals(access) || true) m.put("bank_last4", m.get("bank_last4") == null ? null : "•••• " + m.get("bank_last4"));
        return m;
    }

    /**
     * Creates a property for an existing owner.
     *
     * @param b ownerId, name, address, type and details
     * @return the new property
     */
    @Transactional
    public Map<String, Object> createProperty(Map<String, Object> b) {
        UUID id = UUID.randomUUID();
        UUID ownerId = Req.requiredUuid(b, "ownerId");
        db.one("Owner", "select id from leasing.owner where id = ?", ownerId);
        String state = Req.required(b, "state").toUpperCase();
        if (state.length() != 2) throw ApiException.badRequest("State must be a two-letter code, for example TX");
        db.update("""
                insert into leasing.property (id, org_id, owner_id, name, street, city, state, zip, type, year_built, photo_url, photos, amenities)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)""",
                id, UserContextHolder.orgId(), ownerId, Req.required(b, "name"), Req.required(b, "street"), Req.required(b, "city"), state,
                Req.required(b, "zip"), Optional.ofNullable(Req.str(b, "type")).orElse("MULTIFAMILY"), Req.integer(b, "yearBuilt"),
                Req.str(b, "photoUrl"), db.json(Req.str(b, "photoUrl") == null ? List.of() : List.of(Req.str(b, "photoUrl"))), db.json(Req.strings(b, "amenities")));
        audit.record("Property", id, "CREATED", null, "ACTIVE", Map.of("name", Req.required(b, "name")));
        return Map.of("id", id);
    }

    /**
     * Adds a unit to a property.
     *
     * @param propertyId the property
     * @param b number, bedrooms, bathrooms, size and market rent
     * @return the new unit
     */
    @Transactional
    public Map<String, Object> createUnit(UUID propertyId, Map<String, Object> b) {
        Map<String, Object> p = db.one("Property", "select id, owner_id from leasing.property where id = ?", propertyId);
        UUID id = UUID.randomUUID();
        BigDecimal rent = Req.requiredMoney(b, "marketRent");
        db.update("""
                insert into leasing.unit (id, org_id, property_id, owner_id, number, beds, baths, sqft, market_rent, status, vacant_since)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'VACANT', ?)""",
                id, UserContextHolder.orgId(), propertyId, p.get("owner_id"), Req.required(b, "number"),
                Optional.ofNullable(Req.integer(b, "beds")).orElse(1), new BigDecimal(Optional.ofNullable(Req.str(b, "baths")).orElse("1")),
                Optional.ofNullable(Req.integer(b, "sqft")).orElse(700), rent, LocalDate.now());
        audit.record("Unit", id, "CREATED", null, "VACANT", Map.of("number", Req.required(b, "number")));
        return Map.of("id", id);
    }

    /**
     * Changes a unit's market rent.
     *
     * @throws ApiException (404) when the unit does not exist
     */
    @Transactional
    public void updateUnitRent(UUID unitId, BigDecimal marketRent) {
        if (db.update("update leasing.unit set market_rent = ?, updated_at = now() where id = ?", marketRent, unitId) == 0)
            throw ApiException.notFound("Unit");
        audit.record("Unit", unitId, "MARKET_RENT_CHANGED", null, null, Map.of("marketRent", marketRent));
    }
}
