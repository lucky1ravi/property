package com.propertyos.billing.web;

import com.propertyos.billing.service.*;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API for invoices, payments, collections, owner statements, deposits and tax reports. Each method delegates to one service and is guarded by {@link RequiresPermission}.
 */
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

    /**
     * Lists invoices, optionally by status, resident or kind of charge.
     */
    @GetMapping("/invoices") @RequiresPermission("INVOICE:VIEW")
    public Object invoices(@RequestParam(required = false) String status, @RequestParam(required = false) UUID tenantId, @RequestParam(required = false) String kind) {
        return billing.invoices(status, tenantId, kind);
    }

    /**
     * Returns one invoice with its payments and history.
     */
    @GetMapping("/invoices/{id}") @RequiresPermission("INVOICE:VIEW")
    public Object invoice(@PathVariable UUID id) { return billing.invoice(id); }

    /**
     * Creates a one-off charge for a resident.
     */
    @PostMapping("/invoices") @RequiresPermission("INVOICE:CREATE")
    public Object charge(@RequestBody Map<String, Object> b) { return billing.createCharge(b); }

    /**
     * Voids an invoice. A reason is required and an invoice with payments applied cannot be voided.
     */
    @PostMapping("/invoices/{id}/void") @RequiresPermission("INVOICE:EDIT")
    public Object voidInvoice(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return billing.voidInvoice(id, Req.str(b, "reason")); }

    /**
     * Lists payments, optionally for one resident.
     */
    @GetMapping("/payments") @RequiresPermission("PAYMENT:VIEW")
    public Object payments(@RequestParam(required = false) UUID tenantId) { return billing.payments(tenantId); }

    /**
     * Records a payment and applies it to open invoices.
     */
    @PostMapping("/payments") @RequiresPermission("PAYMENT:CREATE")
    public Object pay(@RequestBody Map<String, Object> b) { return billing.pay(b); }

    /**
     * Quotes the processing fee and total for an amount and payment method before the resident pays.
     */
    @GetMapping("/payments/quote") @RequiresPermission("PAYMENT:VIEW")
    public Object quote(@RequestParam java.math.BigDecimal amount, @RequestParam(required = false) String method, @RequestParam(required = false) UUID tenantId) {
        return billing.quote(amount, method, tenantId);
    }

    /**
     * Returns the company's payment settings: accepted methods, partial payments, autopay, convenience fees and payment limits.
     */
    @GetMapping("/settings") @RequiresPermission("PAYMENT:VIEW")
    public Object settings() { return billing.paymentSettings(); }

    /**
     * Returns the year's income and expense totals for tax reporting; defaults to the current year.
     */
    @GetMapping("/tax/summary") @RequiresPermission("TAX_REPORT:VIEW")
    public Object taxSummary(@RequestParam(required = false) Integer year) { return reporting.taxSummary(year == null ? java.time.LocalDate.now().getYear() : year); }

    /**
     * Returns owners' 1099 status and amounts for a year; defaults to the current year.
     */
    @GetMapping("/tax/1099") @RequiresPermission("TAX_REPORT:VIEW")
    public Object owner1099(@RequestParam(required = false) Integer year) { return reporting.owner1099(year == null ? java.time.LocalDate.now().getYear() : year); }

    /**
     * Records an owner's W-9 status and the last four digits of their tax id.
     */
    @PutMapping("/tax/owners/{ownerId}/w9") @RequiresPermission("TAX_REPORT:EDIT")
    public Object w9(@PathVariable UUID ownerId, @RequestBody Map<String, Object> b) { return reporting.saveW9(ownerId, b); }

    /**
     * Lists upcoming billing deadlines such as deposit refunds.
     */
    @GetMapping("/deadlines") @RequiresPermission("ALERT:VIEW")
    public Object deadlines() { return com.propertyos.common.events.Deadlines.publicView(alerts.deadlines()); }

    /**
     * Sends due billing alerts now instead of waiting for the daily timer.
     */
    @PostMapping("/alerts/run") @RequiresPermission("ALERT:CONFIGURE")
    public Object runAlerts() { return Map.of("sent", alerts.run()); }

    /**
     * Returns the signed-in resident's balance, invoices, payments and saved payment methods.
     */
    @GetMapping("/my") @RequiresPermission("PAYMENT:VIEW")
    public Object mine() { return billing.mine(); }

    /**
     * Saves a payment method (type and last four digits only) for the signed-in resident.
     */
    @PostMapping("/my/methods") @RequiresPermission("PAYMENT:CREATE")
    public Object addMethod(@RequestBody Map<String, Object> b) { return billing.addMethod(b); }

    /**
     * Turns autopay on or off for one of the resident's payment methods.
     */
    @PutMapping("/my/methods/{id}/autopay") @RequiresPermission("PAYMENT:CREATE")
    public void autopay(@PathVariable UUID id, @RequestBody Map<String, Object> b) { billing.setAutopay(id, Req.bool(b, "on")); }

    /**
     * Runs the billing cycle for active leases: creates rent charges, applies late fees and processes autopay according to the company's invoicing settings.
     */
    @PostMapping("/run-cycle") @RequiresPermission("INVOICE:CREATE")
    public Object runCycle() { return billing.runCycle(); }

    /**
     * Returns billed, collected and outstanding totals for the dashboard.
     */
    @GetMapping("/summary") @RequiresPermission("INVOICE:VIEW")
    public Object summary() { return reporting.summary(); }

    /**
     * Returns outstanding balances grouped by how late they are.
     */
    @GetMapping("/aging") @RequiresPermission("INVOICE:VIEW")
    public Object aging() { return reporting.aging(); }

    /**
     * Lists residents with overdue balances.
     */
    @GetMapping("/collections") @RequiresPermission("INVOICE:VIEW")
    public Object collections() { return reporting.collections(); }

    /**
     * Sends payment reminders to one resident, or to all overdue residents when none is given.
     */
    @PostMapping("/collections/remind") @RequiresPermission("INVOICE:EDIT")
    public Object remind(@RequestBody(required = false) Map<String, Object> b) { return reporting.remind(b == null ? null : Req.uuid(b, "tenantId")); }

    /**
     * Lists owner statements.
     */
    @GetMapping("/statements") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statements() { return statements.statements(); }

    /**
     * Returns one owner statement.
     */
    @GetMapping("/statements/{id}") @RequiresPermission("OWNER_STATEMENT:VIEW")
    public Object statement(@PathVariable UUID id) { return statements.statement(id); }

    /**
     * Generates an owner statement for a period.
     */
    @PostMapping("/statements") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object generate(@RequestBody Map<String, Object> b) {
        return statements.generate(Req.requiredUuid(b, "ownerId"), Req.required(b, "period"), Req.required(b, "ownerName"), Req.money(b, "feePercent"));
    }

    /**
     * Publishes a draft statement to the owner.
     */
    @PostMapping("/statements/{id}/publish") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object publish(@PathVariable UUID id) { return statements.publish(id); }

    /**
     * Records a property expense that will appear on the owner's statement.
     */
    @PostMapping("/expenses") @RequiresPermission("OWNER_STATEMENT:CREATE")
    public Object expense(@RequestBody Map<String, Object> b) { return statements.addExpense(b); }

    /**
     * Lists security deposits.
     */
    @GetMapping("/deposits") @RequiresPermission("PAYMENT:VIEW")
    public Object deposits() { return statements.deposits(); }

    /**
     * Settles a deposit, applying deductions and refunding the rest.
     */
    @PostMapping("/deposits/{id}/settle") @RequiresPermission("PAYMENT:APPROVE")
    @SuppressWarnings("unchecked")
    public Object settle(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        Object d = b.get("deductions");
        return statements.settleDeposit(id, d == null ? List.of() : (List<Map<String, Object>>) d);
    }
}
