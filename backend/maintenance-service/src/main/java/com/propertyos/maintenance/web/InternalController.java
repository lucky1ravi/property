package com.propertyos.maintenance.web;

import com.propertyos.common.rls.Db;
import com.propertyos.common.web.Req;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Service-to-service endpoints (token protected, never exposed by the gateway). Used by the document service to link files to jobs and vendors. */
@RestController
@RequestMapping("/internal/maintenance")
public class InternalController {
    private final Db db;

    public InternalController(Db db) {
        this.db = db;
    }

    private static final String WO = """
            select id, org_id, status, title, property_id, owner_id, unit_id, tenant_id, vendor_id, property_name,
                   title || ' – ' || property_name || coalesce(' ' || replace(unit_label, 'Unit ', ''), '') as label
            from maintenance.work_order""";

    @GetMapping("/work-orders-brief")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> workOrders() {
        return db.list(WO + " where status not in ('CANCELLED') order by created_at");
    }

    @GetMapping("/work-orders/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> workOrder(@PathVariable UUID id) {
        return db.one("Work order", WO + " where id = ?", id);
    }

    @GetMapping("/vendors-brief")
    @Transactional(readOnly = true)
    public List<Map<String, Object>> vendors() {
        return db.list("select id, org_id, id as vendor_id, name as label, insurance_expires, active from maintenance.vendor where active order by name");
    }

    @GetMapping("/vendors/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> vendor(@PathVariable UUID id) {
        return db.one("Vendor", "select id, org_id, id as vendor_id, name as label, insurance_expires from maintenance.vendor where id = ?", id);
    }

    /** The newest certificate of insurance on file sets the vendor's insurance date (drives dispatch eligibility and alerts). */
    @PostMapping("/vendors/{id}/insurance")
    @Transactional
    public Map<String, Object> insurance(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        db.update("update maintenance.vendor set insurance_expires = ? where id = ? and (insurance_expires is null or insurance_expires < ?)", Req.date(b, "expiresOn"), id, Req.date(b, "expiresOn"));
        return db.one("Vendor", "select id, insurance_expires from maintenance.vendor where id = ?", id);
    }
}
