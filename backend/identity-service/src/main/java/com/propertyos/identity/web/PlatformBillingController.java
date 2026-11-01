package com.propertyos.identity.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.Req;
import com.propertyos.identity.service.PlatformBilling;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Subscription money, from both sides. The operator's side ({@code /api/identity/platform/billing}) shows what every company is
 * billed and has paid; a company's side ({@code /api/identity/billing}) shows only its own account with PropertyOS.
 */
@RestController
public class PlatformBillingController {
    private final PlatformBilling billing;

    public PlatformBillingController(PlatformBilling billing) {
        this.billing = billing;
    }

    // ---- the operator ------------------------------------------------------------------------------------

    /** Billed, collected, outstanding and overdue, the last six months, the plan mix and who owes the most. */
    @GetMapping("/api/identity/platform/billing/summary") @RequiresPermission("PLATFORM:VIEW")
    public Map<String, Object> summary() {
        return billing.summary();
    }

    /** Every company's invoices, paged. Filter with {@code f.status=OVERDUE}-style column filters or {@code orgId}. */
    @GetMapping("/api/identity/platform/billing/invoices") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> invoices(@RequestParam(required = false) UUID orgId) {
        return billing.invoices(orgId);
    }

    @GetMapping("/api/identity/platform/billing/invoices/{id}") @RequiresPermission("PLATFORM:VIEW")
    public Map<String, Object> invoice(@PathVariable UUID id) {
        return billing.invoice(id, null);
    }

    /** A one-off charge for a company. Body: orgId, amount, description, optional dueDate. */
    @PostMapping("/api/identity/platform/billing/invoices") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> charge(@RequestBody Map<String, Object> b) {
        return billing.createCharge(b);
    }

    @PostMapping("/api/identity/platform/billing/invoices/{id}/void") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> voidInvoice(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return billing.voidInvoice(id, Req.str(b, "reason"));
    }

    /** Records a payment received (wire, cheque, bank, card) or a credit. Body: method, optional amount (default the balance), reference, note. */
    @PostMapping("/api/identity/platform/billing/invoices/{id}/payments") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> record(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return billing.pay(id, b, null);
    }

    @GetMapping("/api/identity/platform/billing/payments") @RequiresPermission("PLATFORM:VIEW")
    public List<Map<String, Object>> payments(@RequestParam(required = false) UUID orgId) {
        return billing.payments(orgId);
    }

    /** One company's whole account: balance, every invoice and every payment. */
    @GetMapping("/api/identity/platform/billing/orgs/{orgId}") @RequiresPermission("PLATFORM:VIEW")
    public Map<String, Object> ledger(@PathVariable UUID orgId) {
        return billing.ledger(orgId);
    }

    /** Makes this month's invoices now (or for the month of {@code asOf}). The same thing runs by itself every night. */
    @PostMapping("/api/identity/platform/billing/run") @RequiresPermission("PLATFORM:CONFIGURE")
    public Map<String, Object> run(@RequestBody(required = false) Map<String, Object> b) {
        return billing.runCycle(b == null ? null : Req.date(b, "asOf"));
    }

    // ---- a company ---------------------------------------------------------------------------------------

    /** The company's own plan, price, balance and invoices. */
    @GetMapping("/api/identity/billing/subscription") @RequiresPermission("SETTINGS:VIEW")
    public Map<String, Object> subscription() {
        return billing.subscription(UserContextHolder.orgId());
    }

    @GetMapping("/api/identity/billing/invoices") @RequiresPermission("SETTINGS:VIEW")
    public List<Map<String, Object>> myInvoices() {
        return billing.invoices(UserContextHolder.orgId());
    }

    @GetMapping("/api/identity/billing/invoices/{id}") @RequiresPermission("SETTINGS:VIEW")
    public Map<String, Object> myInvoice(@PathVariable UUID id) {
        return billing.invoice(id, UserContextHolder.orgId());
    }

    /** Pays one of the company's own invoices by card or bank transfer. Body: method, last4, optional amount. */
    @PostMapping("/api/identity/billing/invoices/{id}/pay") @RequiresPermission("SETTINGS:CONFIGURE")
    public Map<String, Object> pay(@PathVariable UUID id, @RequestBody Map<String, Object> b) {
        return billing.pay(id, b, UserContextHolder.orgId());
    }
}
