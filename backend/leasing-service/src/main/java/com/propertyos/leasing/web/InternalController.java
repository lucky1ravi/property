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

    public InternalController(Db db) {
        this.db = db;
    }

    @GetMapping("/active-leases")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> activeLeases() {
        return db.list("""
                select id, org_id, unit_id, property_id, owner_id, tenant_id, tenant_name, unit_label, property_name, rent, deposit, start_date, end_date, state, terms
                from leasing.lease where status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN')""");
    }

    @GetMapping("/tenant-lease/{tenantId}")
    @Transactional(readOnly = true)
    public Map<String, Object> tenantLease(@PathVariable java.util.UUID tenantId) {
        return db.one("Lease", """
                select id, org_id, unit_id, property_id, owner_id, tenant_id, tenant_name, unit_label, property_name
                from leasing.lease where tenant_id = ? and status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') limit 1""", tenantId);
    }

    @GetMapping("/property/{id}")
    @Transactional(readOnly = true)
    public Map<String, Object> property(@PathVariable java.util.UUID id) {
        return db.one("Property", "select id, name, city, state, owner_id from leasing.property where id = ?", id);
    }

    @GetMapping("/owners/{ownerId}")
    @Transactional(readOnly = true)
    public Map<String, Object> owner(@PathVariable java.util.UUID ownerId) {
        return db.one("Owner", "select id, name, approval_limit, management_fee_pct from leasing.owner where id = ?", ownerId);
    }

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
}
