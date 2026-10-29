package com.propertyos.billing.web;

import com.propertyos.common.rls.Db;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/** Service-to-service endpoints (token protected, never exposed by the gateway). Used by the document service to file bills and receipts. */
@RestController
@RequestMapping("/internal/billing")
public class InternalController {
    private final Db db;

    public InternalController(Db db) {
        this.db = db;
    }

    @GetMapping("/invoices/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> invoice(@PathVariable UUID id) {
        return db.one("Invoice", """
                select id, org_id, property_id, owner_id, tenant_id, null as unit_id, coalesce(invoice_number, '') || ' ' || description || ' – ' || tenant_name as label
                from billing.invoice where id = ?""", id);
    }

    @GetMapping("/statements/{id}/brief")
    @Transactional(readOnly = true)
    public Map<String, Object> statement(@PathVariable UUID id) {
        return db.one("Statement", "select id, org_id, owner_id, null as property_id, 'Owner statement ' || period || ' – ' || owner_name as label from billing.owner_statement where id = ?", id);
    }
}
