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
                left join leasing.unit u on u.property_id = p.id and u.archived_at is null
                where p.archived_at is null
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
                where u.property_id = ? and u.archived_at is null order by u.number""", id));
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
                ? db.list("select u.*, p.name as property_name, p.city, p.state from leasing.unit u join leasing.property p on p.id = u.property_id where u.archived_at is null and p.archived_at is null order by p.name, u.number")
                : db.list("select u.*, p.name as property_name, p.city, p.state from leasing.unit u join leasing.property p on p.id = u.property_id where u.status = ? and u.archived_at is null and p.archived_at is null order by p.name, u.number", status);
    }

    /**
     * Lists owners with property counts and fee terms.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> owners() {
        return db.list("""
                select o.id, o.name, o.contact, o.email, o.phone, o.approval_limit, o.management_fee_pct, o.bank_last4,
                       (select count(*) from leasing.property p where p.owner_id = o.id and p.archived_at is null) as properties,
                       (select count(*) from leasing.unit u where u.owner_id = o.id and u.archived_at is null) as units
                from leasing.owner o where o.archived_at is null order by o.name""").stream().map(this::maskOwner).toList();
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
        if (db.one("Owner", "select archived_at from leasing.owner where id = ?", ownerId).get("archived_at") != null)
            throw ApiException.conflict("This owner is archived. Restore the owner first.");
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
        Map<String, Object> p = db.one("Property", "select id, owner_id, archived_at from leasing.property where id = ?", propertyId);
        if (p.get("archived_at") != null) throw ApiException.conflict("This property is archived. Restore it first.");
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

    /**
     * Adds a property owner.
     *
     * @param b name (required), contact, email, phone, managementFeePct (0-100), approvalLimit
     * @return the new owner's id
     */
    @Transactional
    public Map<String, Object> createOwner(Map<String, Object> b) {
        UUID id = UUID.randomUUID();
        BigDecimal fee = Optional.ofNullable(Req.money(b, "managementFeePct")).orElse(new BigDecimal("8"));
        BigDecimal limit = Optional.ofNullable(Req.money(b, "approvalLimit")).orElse(new BigDecimal("500"));
        if (fee.signum() < 0 || fee.compareTo(new BigDecimal("100")) > 0) throw ApiException.badRequest("Management fee must be between 0 and 100 percent");
        if (limit.signum() < 0) throw ApiException.badRequest("Approval limit can't be negative");
        db.update("insert into leasing.owner (id, org_id, name, contact, email, phone, approval_limit, management_fee_pct) values (?, ?, ?, ?, ?, ?, ?, ?)",
                id, UserContextHolder.orgId(), Req.required(b, "name"), Req.str(b, "contact"), Req.str(b, "email"), Req.str(b, "phone"), limit, fee);
        audit.record("Owner", id, "CREATED", null, "ACTIVE", Map.of("name", Req.required(b, "name")));
        return Map.of("id", id);
    }

    /** Changes an owner's details and management terms. Only the fields sent are changed. */
    @Transactional
    public void updateOwner(UUID id, Map<String, Object> b) {
        BigDecimal fee = Req.money(b, "managementFeePct"), limit = Req.money(b, "approvalLimit");
        if (fee != null && (fee.signum() < 0 || fee.compareTo(new BigDecimal("100")) > 0)) throw ApiException.badRequest("Management fee must be between 0 and 100 percent");
        if (limit != null && limit.signum() < 0) throw ApiException.badRequest("Approval limit can't be negative");
        if (db.update("""
                update leasing.owner set name = coalesce(?, name), contact = coalesce(?, contact), email = coalesce(?, email), phone = coalesce(?, phone),
                       management_fee_pct = coalesce(?, management_fee_pct), approval_limit = coalesce(?, approval_limit), updated_at = now() where id = ?""",
                Req.str(b, "name"), Req.str(b, "contact"), Req.str(b, "email"), Req.str(b, "phone"), fee, limit, id) == 0)
            throw ApiException.notFound("Owner");
        audit.record("Owner", id, "UPDATED", null, null, Map.of("fields", b.keySet().toString()));
    }

    /** Changes a property's name, address, type or year built. Only the fields sent are changed. */
    @Transactional
    public void updateProperty(UUID id, Map<String, Object> b) {
        String state = Req.str(b, "state");
        if (state != null && state.trim().length() != 2) throw ApiException.badRequest("State must be a two-letter code, for example TX");
        if (db.update("""
                update leasing.property set name = coalesce(?, name), street = coalesce(?, street), city = coalesce(?, city), state = coalesce(?, state),
                       zip = coalesce(?, zip), type = coalesce(?, type), year_built = coalesce(?, year_built), updated_at = now() where id = ?""",
                Req.str(b, "name"), Req.str(b, "street"), Req.str(b, "city"), state == null ? null : state.trim().toUpperCase(), Req.str(b, "zip"),
                Req.str(b, "type"), Req.integer(b, "yearBuilt"), id) == 0)
            throw ApiException.notFound("Property");
        audit.record("Property", id, "UPDATED", null, null, Map.of("fields", b.keySet().toString()));
    }

    /** Changes a unit's number, bedrooms, bathrooms, size or market rent. Only the fields sent are changed. */
    @Transactional
    public void updateUnit(UUID id, Map<String, Object> b) {
        BigDecimal rent = Req.money(b, "marketRent");
        if (rent != null && rent.signum() <= 0) throw ApiException.badRequest("Market rent must be more than zero");
        String baths = Req.str(b, "baths");
        if (db.update("""
                update leasing.unit set number = coalesce(?, number), beds = coalesce(?, beds), baths = coalesce(?, baths), sqft = coalesce(?, sqft),
                       market_rent = coalesce(?, market_rent), updated_at = now() where id = ?""",
                Req.str(b, "number"), Req.integer(b, "beds"), baths == null ? null : new BigDecimal(baths), Req.integer(b, "sqft"), rent, id) == 0)
            throw ApiException.notFound("Unit");
        audit.record("Unit", id, "UPDATED", null, null, Map.of("fields", b.keySet().toString()));
    }

    /** People who have a lease or an application, so a resident's sign-in can be linked to them. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> residents() {
        return db.list("""
                select p.id, p.first_name || ' ' || p.last_name as name, p.email,
                       (select count(*) from leasing.lease l where l.tenant_id = p.id and l.status = 'ACTIVE') as active_leases
                from leasing.person p
                where exists (select 1 from leasing.lease l where l.tenant_id = p.id) order by name""");
    }

    // ---------- Archive ----------
    /**
     * Archives an owner. Blocked while the owner still has properties, so nothing is left without an owner.
     *
     * @throws ApiException (404) unknown owner, (409) the owner still has properties
     */
    @Transactional
    public void archiveOwner(UUID id) {
        db.one("Owner", "select id from leasing.owner where id = ?", id);
        Long n = db.value(Long.class, "select count(*) from leasing.property where owner_id = ? and archived_at is null", id);
        if (n != null && n > 0) throw ApiException.conflict("This owner still has " + n + (n == 1 ? " property" : " properties") + ". Archive them first.");
        db.update("update leasing.owner set archived_at = now() where id = ? and archived_at is null", id);
        audit.record("Owner", id, "ARCHIVED", "ACTIVE", "ARCHIVED", Map.of());
    }

    /**
     * Archives a property and its vacant units. Blocked while any unit is occupied or has an open listing.
     *
     * @throws ApiException (404) unknown property, (409) in use
     */
    @Transactional
    public void archiveProperty(UUID id) {
        db.one("Property", "select id from leasing.property where id = ?", id);
        Long busy = db.value(Long.class, "select count(*) from leasing.unit where property_id = ? and archived_at is null and status <> 'VACANT'", id);
        if (busy != null && busy > 0) throw ApiException.conflict(busy + (busy == 1 ? " unit is" : " units are") + " occupied. A property can be archived once everyone has moved out.");
        Long listed = db.value(Long.class, "select count(*) from leasing.listing where property_id = ? and status <> 'LEASED'", id);
        if (listed != null && listed > 0) throw ApiException.conflict("This property has open listings. Remove or finish them first.");
        db.update("update leasing.unit set archived_at = now() where property_id = ? and archived_at is null", id);
        db.update("update leasing.property set archived_at = now() where id = ? and archived_at is null", id);
        audit.record("Property", id, "ARCHIVED", "ACTIVE", "ARCHIVED", Map.of());
    }

    /**
     * Archives a unit. Blocked while it is occupied or has an open listing.
     *
     * @throws ApiException (404) unknown unit, (409) in use
     */
    @Transactional
    public void archiveUnit(UUID id) {
        Map<String, Object> u = db.one("Unit", "select id, status, number from leasing.unit where id = ?", id);
        if (!"VACANT".equals(u.get("status"))) throw ApiException.conflict("Unit " + u.get("number") + " is occupied. Archive it once the resident has moved out.");
        Long listed = db.value(Long.class, "select count(*) from leasing.listing where unit_id = ? and status <> 'LEASED'", id);
        if (listed != null && listed > 0) throw ApiException.conflict("Unit " + u.get("number") + " has an open listing. Remove or finish it first.");
        db.update("update leasing.unit set archived_at = now() where id = ? and archived_at is null", id);
        audit.record("Unit", id, "ARCHIVED", "VACANT", "ARCHIVED", Map.of());
    }

    /** Brings an archived owner, property (with its units) or unit back. kind is owner, property or unit. */
    @Transactional
    public void restore(String kind, UUID id) {
        switch (kind) {
            case "owner" -> {
                db.one("Owner", "select id from leasing.owner where id = ?", id);
                db.update("update leasing.owner set archived_at = null where id = ?", id);
            }
            case "property" -> {
                Map<String, Object> p = db.one("Property", "select owner_id from leasing.property where id = ?", id);
                if (db.one("Owner", "select archived_at from leasing.owner where id = ?", (UUID) p.get("owner_id")).get("archived_at") != null)
                    throw ApiException.conflict("Restore the owner first.");
                db.update("update leasing.property set archived_at = null where id = ?", id);
                db.update("update leasing.unit set archived_at = null where property_id = ?", id);
            }
            case "unit" -> {
                Map<String, Object> u = db.one("Unit", "select property_id from leasing.unit where id = ?", id);
                if (db.one("Property", "select archived_at from leasing.property where id = ?", (UUID) u.get("property_id")).get("archived_at") != null)
                    throw ApiException.conflict("Restore the property first.");
                db.update("update leasing.unit set archived_at = null where id = ?", id);
            }
            default -> throw ApiException.notFound("Record");
        }
        audit.record(kind.substring(0, 1).toUpperCase() + kind.substring(1), id, "RESTORED", "ARCHIVED", "ACTIVE", Map.of());
    }
}
