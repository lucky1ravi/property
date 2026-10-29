package com.propertyos.billing.web;

import com.propertyos.billing.service.*;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/billing")
public class BillingController {
    private final BillingService billing;
    private final ReportingService reporting;
    private final StatementService statements;
    private final BillingAlerts alerts;

    public BillingController(BillingService billing, ReportingService reporting, StatementService statements, BillingAlerts alerts) {
        this.alerts = alerts;
        this.billing = billing;
        this.reporting = reporting;
        this.statements = statements;
    }

    @GetMapping("/invoices") @RequiresPermission("INVOICE:VIEW")
    public Object invoices(@RequestParam(required = false) String status, @RequestParam(required = false) UUID tenantId, @RequestParam(required = false) String kind) {
        return billing.invoices(status, tenantId, kind);
    }

    @GetMapping("/invoices/{id}") @RequiresPermission("INVOICE:VIEW")
    public Object invoice(@PathVariable UUID id) { return billing.invoice(id); }

    @PostMapping("/invoices") @RequiresPermission("INVOICE:CREATE")
    public Object charge(@RequestBody Map<String, Object> b) { return billing.createCharge(b); }

    @PostMapping("/invoices/{id}/void") @RequiresPermission("INVOICE:EDIT")
    public Object voidInvoice(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return billing.voidInvoice(id, Req.str(b, "reason")); }

    @GetMapping("/payments") @RequiresPermission("PAYMENT:VIEW")
    public Object payments(@RequestParam(required = false) UUID tenantId) { return billing.payments(tenantId); }

    @PostMapping("/payments") @RequiresPermission("PAYMENT:CREATE")
    public Object pay(@RequestBody Map<String, Object> b) { return billing.pay(b); }

    @GetMapping("/payments/quote") @RequiresPermission("PAYMENT:VIEW")
    public Object quote(@RequestParam java.math.BigDecimal amount, @RequestParam(required = false) String method, @RequestParam(required = false) UUID tenantId) {
        return billing.quote(amount, method, tenantId);
    }

    @GetMapping("/settings") @RequiresPermission("PAYMENT:VIEW")
    public Object settings() { return billing.paymentSettings(); }

    @GetMapping("/tax/summary") @RequiresPermission("TAX_REPORT:VIEW")
    public Object taxSummary(@RequestParam(required = false) Integer year) { return reporting.taxSummary(year == null ? java.time.LocalDate.now().getYear() : year); }

    @GetMapping("/tax/1099") @RequiresPermission("TAX_REPORT:VIEW")
    public Object owner1099(@RequestParam(required = false) Integer year) { return reporting.owner1099(year == null ? java.time.LocalDate.now().getYear() : year); }

    @PutMapping("/tax/owners/{ownerId}/w9") @RequiresPermission("TAX_REPORT:EDIT")
    public Object w9(@PathVariable UUID ownerId, @RequestBody Map<String, Object> b) { return reporting.saveW9(ownerId, b); }

    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return com.propertyos.common.events.Deadlines.publicView(alerts.deadlines()); }

    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object runAlerts() { return Map.of("sent", alerts.run()); }

    @GetMapping("/my") @RequiresPermission("PAYMENT:VIEW")
    public Object mine() { return billing.mine(); }

    @PostMapping("/my/methods") @RequiresPermission("PAYMENT:CREATE")
    public Object addMethod(@RequestBody Map<String, Object> b) { return billing.addMethod(b); }

    @PutMapping("/my/methods/{id}/autopay") @RequiresPermission("PAYMENT:CREATE")
    public void autopay(@PathVariable UUID id, @RequestBody Map<String, Object> b) { billing.setAutopay(id, Req.bool(b, "on")); }

    @PostMapping("/run-cycle") @RequiresPermission("INVOICE:CREATE")
    public Object runCycle() { return billing.runCycle(); }

    @GetMapping("/summary") @RequiresPermission("INVOICE:VIEW")
    public Object summary() { return reporting.summary(); }

    @GetMapping("/aging") @RequiresPermission("INVOICE:VIEW")
    public Object aging() { return reporting.aging(); }

    @GetMapping("/collections") @RequiresPermission("INVOICE:VIEW")
    public Object collections() { return reporting.collections(); }

    @PostMapping("/collections/remind") @RequiresPermission("INVOICE:EDIT")
    public Object remind(@RequestBody(required = false) Map<String, Object> b) { return reporting.remind(b == null ? null : Req.uuid(b, "tenantId")); }

    @GetMapping("/statements") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statements() { return statements.statements(); }

    @GetMapping("/statements/{id}") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statement(@PathVariable UUID id) { return statements.statement(id); }

    @PostMapping("/statements") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object generate(@RequestBody Map<String, Object> b) {
        return statements.generate(Req.requiredUuid(b, "ownerId"), Req.required(b, "period"), Req.required(b, "ownerName"), Req.money(b, "feePercent"));
    }

    @PostMapping("/statements/{id}/publish") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object publish(@PathVariable UUID id) { return statements.publish(id); }

    @PostMapping("/expenses") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object expense(@RequestBody Map<String, Object> b) { return statements.addExpense(b); }

    @GetMapping("/deposits") @RequiresPermission("PAYMENT:VIEW")
    public Object deposits() { return statements.deposits(); }

    @PostMapping("/deposits/{id}/settle") @RequiresPermission("PAYMENT:APPROVE")
    @SuppressWarnings("unchecked")
    public Object settle(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        Object d = b.get("deductions");
        return statements.settleDeposit(id, d == null ? List.of() : (List<Map<String, Object>>) d);
    }
}
