package com.propertyos.leasing.web;

import com.propertyos.common.rls.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** Service-to-service endpoints (token protected, never exposed by the gateway). */
@RestController
@RequestMapping("/internal/leasing")
public class InternalController {
    private final Db db;
    private final com.propertyos.leasing.service.ApplicationService applications;

    public InternalController(Db db, com.propertyos.leasing.service.ApplicationService applications) {
        this.db = db;
        this.applications = applications;
    }

    /** Automation hook: runs screening as the system as soon as an application arrives. */
    @PostMapping("/applications/{id}/screen")
    public Map<String, Object> screen(@PathVariable java.util.UUID id) {
        return Map.of("status", applications.screen(id).get("status"));
    }

    /**
     * Service-to-service: active leases with rent, term and parties, for billing to generate charges.
     */
    @GetMapping("/active-leases")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeLeases() {
        return db.list("""
                select id, org_id, unit_id, property_id, owner_id, tenant_id, tenant_name, unit_label, property_name, rent, deposit, start_date, end_date, state, terms
                from leasing.lease where status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN')""");
    }

    /** Which properties each owner and resident is connected to, so messaging can be limited to people on the same properties. */
    @GetMapping("/party-directory")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> partyDirectory() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        out.addAll(db.list("select owner_id as party_id, 'OWNER' as type, array_to_string(array_agg(id), ',') as property_ids from leasing.property group by owner_id"));
        out.addAll(db.list("select tenant_id as party_id, 'TENANT' as type, array_to_string(array_agg(distinct property_id), ',') as property_ids from leasing.lease where status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') group by tenant_id"));
        return out;
    }

    /**
     * Service-to-service: the lease of a resident.
     */
    @GetMapping("/tenant-lease/{tenantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> tenantLease(@PathVariable java.util.UUID tenantId) {
        return db.one("Lease", """
                select id, org_id, unit_id, property_id, owner_id, tenant_id, tenant_name, unit_label, property_name
                from leasing.lease where tenant_id = ? and status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') limit 1""", tenantId);
    }

    /**
     * Service-to-service: basic facts about a property.
     */
    @GetMapping("/property/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> property(@PathVariable java.util.UUID id) {
        return db.one("Property", "select id, name, city, state, owner_id from leasing.property where id = ?", id);
    }

    /** Adds an approved photo to a property gallery; the first photo also becomes the cover image. */
    @PostMapping("/property/{id}/photo")
    @Transactional
    public Map<String, Object> addPhoto(@PathVariable java.util.UUID id, @RequestBody Map<String, Object> b) {
        String url = String.valueOf(b.get("url"));
        db.update("""
                update leasing.property set
                  photos = case when photos @> to_jsonb(?::text) then photos else photos || to_jsonb(?::text) end,
                  photo_url = case when photo_url is null or photo_url = '' then ? else photo_url end
                where id = ?""", url, url, url, id);
        return Map.of("added", true);
    }

    /**
     * Service-to-service: removes a listing or property photo after the document service rejects it.
     */
    @PostMapping("/property/{id}/photo/remove")
    @Transactional
    public Map<String, Object> removePhoto(@PathVariable java.util.UUID id, @RequestBody Map<String, Object> b) {
        String url = String.valueOf(b.get("url"));
        db.update("""
                update leasing.property set
                  photos = coalesce((select jsonb_agg(e) from jsonb_array_elements(photos) e where e <> to_jsonb(?::text)), '[]'::jsonb),
                  photo_url = case when photo_url = ? then (select e #>> '{}' from jsonb_array_elements(photos) e where e <> to_jsonb(?::text) limit 1) else photo_url end
                where id = ?""", url, url, url, id);
        return Map.of("removed", true);
    }

    /**
     * Service-to-service: an owner's approval limit and management fee.
     */
    @GetMapping("/owners/{ownerId}")
    @Transactional(readOnly = true)
    public Map<String, Object> owner(@PathVariable java.util.UUID ownerId) {
        return db.one("Owner", "select id, name, approval_limit, management_fee_pct from leasing.owner where id = ?", ownerId);
    }

    /**
     * Service-to-service: unit counts by status for the portfolio.
     */
    @GetMapping("/occupancy")
    @Transactional(readOnly = true)
    public Map<String, Object> occupancy() {
        return db.one("Portfolio", """
                select count(*) as units, count(*) filter (where status = 'OCCUPIED') as occupied, count(*) filter (where status = 'VACANT') as vacant,
                       coalesce(sum(market_rent) filter (where status = 'VACANT'), 0) as vacant_market_rent,
                       coalesce(sum(current_rent) filter (where status = 'OCCUPIED'), 0) as rent_roll,
                       coalesce(sum(market_rent - current_rent) filter (where status = 'OCCUPIED' and market_rent > current_rent), 0) as below_market_gap
                from leasing.unit""");
    }

    // ---- Lookups for the document service (it links every file to the record's own property, owner and resident).
    private static final String LEASE_DOC_COLS = """
            id, org_id, status, unit_id, property_id, owner_id, tenant_id, tenant_name, tenant_email, unit_label, property_name, property_address,
            start_date, end_date, rent, deposit, state, document_text, tenant_signature, tenant_signed_at, tenant_sign_ip, manager_signature, manager_signed_at,
            property_name || ' ' || replace(unit_label, 'Unit ', '') as home,
            tenant_name || ' \u2013 ' || property_name || ' ' || replace(unit_label, 'Unit ', '') as label""";

    /**
     * Service-to-service: short descriptions of applications, used to attach documents to them.
     */
    @GetMapping("/applications-brief")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> applicationsBrief() {
        return db.list("""
                select id, org_id, status, applicant_name, applicant_email, applicant_name || ' \u2013 ' || unit_label as label, property_id, unit_id, owner_id
                from leasing.application where status in ('SUBMITTED','DOCS_PENDING','SCREENED','OWNER_REVIEW','APPROVED') order by created_at""");
    }

    /**
     * Service-to-service: a short description of one application.
     */
    @GetMapping("/applications/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> applicationBrief(@PathVariable java.util.UUID id) {
        return db.one("Application", "select id, org_id, status, applicant_name, applicant_email, applicant_name || ' \u2013 ' || unit_label as label, property_id, unit_id, owner_id from leasing.application where id = ?", id);
    }

    /**
     * Service-to-service: short descriptions of leases, used to attach documents to them.
     */
    @GetMapping("/leases-brief")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leasesBrief() {
        return db.list("""
                select id, org_id, status, property_id, unit_id, owner_id, tenant_id, tenant_name || ' \u2013 ' || property_name || ' ' || replace(unit_label, 'Unit ', '') as label
                from leasing.lease where status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') order by property_name, unit_label""");
    }

    /**
     * Service-to-service: the lease fields needed to generate lease documents.
     */
    @GetMapping("/leases/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> leaseBrief(@PathVariable java.util.UUID id) {
        return db.one("Lease", "select " + LEASE_DOC_COLS + " from leasing.lease where id = ?", id);
    }

    /** Every lease past draft, with its text and signatures, so the lease agreement can be filed and kept current. */
    @GetMapping("/lease-documents")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leaseDocuments() {
        return db.list("select " + LEASE_DOC_COLS + " from leasing.lease where status <> 'DRAFT' order by created_at");
    }

    /**
     * Service-to-service: a short description of a resident, used to attach documents to them.
     */
    @GetMapping("/tenants/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> tenantBrief(@PathVariable java.util.UUID id) {
        return db.find("""
                select tenant_id, property_id, unit_id, owner_id, property_name, tenant_name || ' \u2013 ' || property_name || ' ' || replace(unit_label, 'Unit ', '') as label
                from leasing.lease where tenant_id = ? order by case when status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') then 0 else 1 end, start_date desc limit 1""", id)
                .orElseGet(() -> db.one("Resident", "select id as tenant_id, first_name || ' ' || last_name as label from leasing.person where id = ?", id));
    }

    /**
     * Service-to-service: owners as selectable labels.
     */
    @GetMapping("/owners-brief")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> ownersBrief() {
        return db.list("select id, org_id, name as label, null as property_id from leasing.owner order by name");
    }
}
