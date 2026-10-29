package com.propertyos.billing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Invoices, payments (simulated gateway), autopay, late fees and the ledger. */
@Service
public class BillingService {
    private final Db db;
    private final AuditService audit;
    private final EventPublisher events;
    private final Notifier notifier;
    private final ConfigClient config;
    private final ServiceClient client;

    public BillingService(Db db, AuditService audit, EventPublisher events, Notifier notifier, ConfigClient config, ServiceClient client) {
        this.db = db;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
        this.config = config;
        this.client = client;
    }

    // ---------- Invoices ----------
    @Transactional(readOnly = true)
    public List<Map<String, Object>> invoices(String status, UUID tenantId, String kind) {
        StringBuilder sql = new StringBuilder("select i.*, (amount - paid) as balance, greatest(current_date - due_date, 0) as days_overdue from billing.invoice i where 1=1");
        List<Object> a = new ArrayList<>();
        if (status != null) {
            if ("OVERDUE".equals(status)) sql.append(" and status in ('OPEN','PARTIAL') and due_date < current_date");
            else { sql.append(" and status = ?"); a.add(status); }
        }
        if (tenantId != null) { sql.append(" and tenant_id = ?"); a.add(tenantId); }
        if (kind != null) { sql.append(" and kind = ?"); a.add(kind); }
        sql.append(" order by due_date desc, created_at desc limit 500");
        return db.list(sql.toString(), a.toArray());
    }

    @Transactional(readOnly = true)
    public Map<String, Object> invoice(UUID id) {
        Map<String, Object> i = new LinkedHashMap<>(db.one("Invoice", "select i.*, (amount - paid) as balance from billing.invoice i where id = ?", id));
        i.put("payments", db.list("select * from billing.payment where allocations @> ?::jsonb order by created_at desc", db.json(List.of(Map.of("invoiceId", id.toString())))));
        i.put("timeline", audit.timeline(id));
        return i;
    }

    /** Manual charge (utility bill-back, pet fee, damage). */
    @Transactional
    public Map<String, Object> createCharge(Map<String, Object> b) {
        UUID tenantId = Req.requiredUuid(b, "tenantId");
        Map<String, Object> lease = leaseFor(tenantId);
        BigDecimal amount = Req.requiredMoney(b, "amount");
        if (amount.signum() <= 0) throw ApiException.badRequest("Amount must be greater than zero.");
        BigDecimal limit = UserContextHolder.require().limit("INVOICE");
        if (limit != null && amount.compareTo(limit) > 0)
            throw ApiException.forbidden("Amount exceeds your charge approval limit of " + limit + ".");
        String chargeKind = Optional.ofNullable(Req.str(b, "kind")).orElse("OTHER");
        if ("LATE_FEE".equals(chargeKind)) {
            BigDecimal cap = lateFeeCap(String.valueOf(lease.get("state")), (BigDecimal) lease.getOrDefault("rent", BigDecimal.ZERO));
            if (cap != null && amount.compareTo(cap) > 0)
                throw ApiException.badRequest("A late fee in " + lease.get("state") + " can't be more than $" + cap + " under your state rules.");
        }
        UUID id = insertInvoice(lease, chargeKind, Req.required(b, "description"), null, amount,
                Optional.ofNullable(Req.date(b, "dueDate")).orElse(LocalDate.now().plusDays(10)));
        notifier.toParty(tenantId, "New charge on your account", Req.required(b, "description") + ": $" + amount, "/payments");
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> voidInvoice(UUID id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void an invoice.");
        Map<String, Object> i = db.one("Invoice", "select * from billing.invoice where id = ?", id);
        if (((BigDecimal) i.get("paid")).signum() > 0) throw ApiException.conflict("This invoice has payments applied. Refund them before voiding.");
        if ("VOID".equals(i.get("status"))) return invoice(id);
        db.update("update billing.invoice set status = 'VOID', void_reason = ?, updated_at = now() where id = ?", reason, id);
        ledger(i, "CHARGE", ((BigDecimal) i.get("amount")).negate(), "Voided: " + i.get("description"), "INVOICE", id);
        audit.record("Invoice", id, "VOID", (String) i.get("status"), "VOID", Map.of("reason", reason));
        return invoice(id);
    }

    UUID insertInvoice(Map<String, Object> lease, String kind, String description, String period, BigDecimal subtotal, LocalDate due) {
        UUID id = UUID.randomUUID();
        String state = lease.get("state") == null ? null : String.valueOf(lease.get("state"));
        BigDecimal rate = taxRate(kind, state);
        BigDecimal tax = subtotal.multiply(rate).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal amount = subtotal.add(tax);
        String number = config.text("payments/invoice/numberPrefix", "INV").replaceAll("[^A-Za-z0-9]", "") + "-" + LocalDate.now().getYear() + "-"
                + String.format("%06d", db.value(Long.class, "select nextval('billing.invoice_seq')"));
        db.update("""
                insert into billing.invoice (id, org_id, property_id, owner_id, tenant_id, lease_id, tenant_name, unit_label, kind, description, period, amount, due_date, created_by,
                                             invoice_number, subtotal, tax_rate, tax_amount, state)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                id, lease.get("org_id"), lease.get("property_id"), lease.get("owner_id"), lease.get("tenant_id"), lease.get("id"),
                lease.get("tenant_name"), lease.get("property_name") + " · " + lease.get("unit_label"), kind, description, period, amount, due,
                UserContextHolder.get() == null ? "System" : UserContextHolder.get().name(), number, subtotal, rate, tax, state);
        Map<String, Object> inv = Map.of("org_id", lease.get("org_id"), "property_id", lease.get("property_id"), "owner_id", lease.get("owner_id"), "tenant_id", lease.get("tenant_id"));
        ledger(inv, "CHARGE", amount, description + (tax.signum() > 0 ? " (incl. $" + tax + " tax)" : ""), "INVOICE", id);
        audit.record("Invoice", id, "CREATED", null, "OPEN", Map.of("amount", amount, "kind", kind, "tax", tax, "number", number));
        return id;
    }

    // ---------- Tax, fee caps and convenience fees (all driven by the "payments" and "stateRules" configuration) ----------
    /** Sales or rental tax rate for this kind of charge in this state. Deposits and returned-payment fees are never taxed. */
    BigDecimal taxRate(String kind, String state) {
        if (!config.flag("payments/tax/enabled", true)) return BigDecimal.ZERO;
        String flag = switch (kind) {
            case "RENT" -> "taxRent";
            case "LATE_FEE" -> "taxLateFees";
            case "UTILITY" -> "taxUtilities";
            case "APPLICATION" -> "taxApplicationFees";
            case "OTHER", "FEE" -> "taxOther";
            default -> null;
        };
        if (flag == null || !config.flag("payments/tax/" + flag, false)) return BigDecimal.ZERO;
        JsonNode r = state == null ? null : config.at("payments/tax/rates/" + state);
        BigDecimal pct = r == null || r.isMissingNode() || r.isNull() ? config.number("payments/tax/defaultRatePercent", "0") : new BigDecimal(r.asText());
        return pct.max(BigDecimal.ZERO).min(new BigDecimal("30"));
    }

    /** Highest late fee allowed in the state (lower of the percentage and flat caps), or null when the state sets no cap. */
    BigDecimal lateFeeCap(String state, BigDecimal rent) {
        if (!config.flag("payments/enforceStateFeeCaps", true)) return null;
        JsonNode rule = config.stateRule(state);
        BigDecimal cap = null;
        JsonNode pct = rule.path("lateFeeCapPercent");
        if (pct.isNumber()) cap = rent.multiply(pct.decimalValue()).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        JsonNode flat = rule.path("lateFeeCapFlat");
        if (flat.isNumber()) cap = cap == null ? flat.decimalValue() : cap.min(flat.decimalValue());
        return cap;
    }

    /** Convenience fee the payer is charged on top of the amount applied to the account. */
    BigDecimal convenienceFee(BigDecimal amount, String method, String state) {
        if ("CARD".equals(method)) {
            BigDecimal pct = config.number("payments/convenienceFees/cardPercent", "0").min(new BigDecimal("3")).max(BigDecimal.ZERO);   // card networks cap surcharges at 3%
            JsonNode blocked = config.at("payments/convenienceFees/surchargeBlockedStates");
            if (state != null && blocked.isArray()) for (JsonNode n : blocked) if (state.equalsIgnoreCase(n.asText())) return BigDecimal.ZERO;
            return amount.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        }
        return config.number("payments/convenienceFees/achFlat", "0").max(BigDecimal.ZERO);
    }

    /** What the payer will be charged, shown before they confirm. */
    @Transactional(readOnly = true)
    public Map<String, Object> quote(BigDecimal amount, String method, UUID tenantId) {
        UUID t = tenantId != null ? tenantId : UserContextHolder.require().partyId();
        String state = t == null ? null : db.find("select state from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL') order by due_date limit 1", t)
                .map(m -> (String) m.get("state")).orElse(null);
        String m = method == null ? "ACH" : method.toUpperCase();
        BigDecimal fee = convenienceFee(amount, m, state);
        return Map.of("amount", amount, "fee", fee, "total", amount.add(fee), "method", m);
    }

    /** Settings every signed-in user may read: which methods are accepted, fee rules and the invoice header. */
    public Map<String, Object> paymentSettings() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("methods", config.at("payments/methods"));
        out.put("allowPartialPayments", config.flag("payments/allowPartialPayments", true));
        out.put("autopayEnabled", config.flag("payments/autopayEnabled", true));
        out.put("convenienceFees", config.at("payments/convenienceFees"));
        out.put("invoice", config.at("payments/invoice"));
        out.put("minimumPayment", config.number("payments/minimumPayment", "1"));
        out.put("maximumPayment", config.number("payments/maximumPayment", "50000"));
        return out;
    }

    void ledger(Map<String, Object> ctx, String type, BigDecimal amount, String memo, String refType, UUID refId) {
        db.update("insert into billing.ledger_entry (id, org_id, property_id, owner_id, tenant_id, type, amount, memo, ref_type, ref_id) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), ctx.get("org_id"), ctx.get("property_id"), ctx.get("owner_id"), ctx.get("tenant_id"), type, amount, memo, refType, refId);
    }

    // ---------- Payments ----------
    @Transactional(readOnly = true)
    public List<Map<String, Object>> payments(UUID tenantId) {
        return tenantId == null ? db.list("select * from billing.payment order by created_at desc limit 300")
                : db.list("select * from billing.payment where tenant_id = ? order by created_at desc", tenantId);
    }

    /**
     * Takes a payment from the simulated gateway and applies it. With no invoice specified it is allocated oldest-first.
     * Test card/bank numbers ending in 0002 are declined to demonstrate failure handling.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Map<String, Object> pay(Map<String, Object> b) {
        UserContext ctx = UserContextHolder.require();
        UUID tenantId = Optional.ofNullable(Req.uuid(b, "tenantId")).orElse(ctx.partyId());
        if (tenantId == null) throw ApiException.badRequest("Choose which tenant this payment is for.");
        if ("TENANT".equals(ctx.scope()) && !tenantId.equals(ctx.partyId())) throw ApiException.forbidden("You can only pay your own balance.");
        BigDecimal amount = Req.requiredMoney(b, "amount");
        if (amount.signum() <= 0) throw ApiException.badRequest("Amount must be greater than zero.");
        UUID invoiceId = Req.uuid(b, "invoiceId");
        String method = Optional.ofNullable(Req.str(b, "method")).orElse("ACH").toUpperCase();
        String last4 = Optional.ofNullable(Req.str(b, "last4")).orElse("6789");
        return takePayment(tenantId, amount, invoiceId, method, last4, "MANUAL");
    }

    Map<String, Object> takePayment(UUID tenantId, BigDecimal amount, UUID invoiceId, String method, String last4, String source) {
        List<Map<String, Object>> open = invoiceId != null
                ? db.list("select * from billing.invoice where id = ? and tenant_id = ? and status in ('OPEN','PARTIAL')", invoiceId, tenantId)
                : db.list("select * from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL') order by due_date, created_at", tenantId);
        if (open.isEmpty()) throw ApiException.conflict("Nothing is due on this account.");
        String payState = (String) open.get(0).get("state");
        if ("MANUAL".equals(source)) {
            if (!config.flag("payments/methods/" + method.toLowerCase(), true)) throw ApiException.badRequest("Your property manager doesn't accept " + ("CARD".equals(method) ? "card" : "bank (ACH)") + " payments.");
            BigDecimal min = config.number("payments/minimumPayment", "1"), max = config.number("payments/maximumPayment", "50000");
            if (amount.compareTo(min) < 0) throw ApiException.badRequest("The minimum payment is $" + min + ".");
            if (amount.compareTo(max) > 0) throw ApiException.badRequest("The maximum single payment is $" + max + ". Contact your property manager for larger amounts.");
        }
        BigDecimal totalDue = open.stream().map(i -> ((BigDecimal) i.get("amount")).subtract((BigDecimal) i.get("paid"))).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (amount.compareTo(totalDue) > 0) throw ApiException.badRequest("That's more than the $" + totalDue + " due. Enter an amount up to the balance.");
        if ("MANUAL".equals(source) && amount.compareTo(totalDue) < 0 && !config.flag("payments/allowPartialPayments", true))
            throw ApiException.badRequest("Partial payments aren't accepted. Pay the full balance of $" + totalDue + ".");
        BigDecimal convFee = convenienceFee(amount, method, payState);
        Map<String, Object> first = open.get(0);
        UUID pid = UUID.randomUUID();
        String ref = "PAY-" + pid.toString().substring(0, 8).toUpperCase();
        boolean declined = last4.endsWith("0002");
        Map<String, Object> pctx = Map.of("org_id", first.get("org_id"), "property_id", first.get("property_id"), "owner_id", first.get("owner_id"), "tenant_id", tenantId);
        if (declined) {
            db.update("""
                    insert into billing.payment (id, org_id, property_id, owner_id, tenant_id, tenant_name, amount, method, method_last4, status, reference, failure_reason, source)
                    values (?,?,?,?,?,?,?,?,?,'FAILED',?,?,?)""", pid, first.get("org_id"), first.get("property_id"), first.get("owner_id"), tenantId,
                    first.get("tenant_name"), amount, method, last4, ref, "Insufficient funds", source);
            if ("ACH".equals(method)) chargeReturnedPaymentFee(tenantId, payState, first);
            events.publish("PaymentFailed", "Payment", pid, Map.of("tenantId", tenantId.toString(), "amount", amount.toString(), "reason", "Insufficient funds"));
            notifier.toParty(tenantId, "Payment didn't go through", "Your " + method + " payment of $" + amount + " was declined. Update your payment method.", "/payments");
            throw ApiException.conflict("The payment was declined (insufficient funds). Reference " + ref + ". No charge was made.");
        }
        BigDecimal remaining = amount;
        List<Map<String, Object>> allocations = new ArrayList<>();
        for (Map<String, Object> inv : open) {
            if (remaining.signum() <= 0) break;
            BigDecimal bal = ((BigDecimal) inv.get("amount")).subtract((BigDecimal) inv.get("paid"));
            BigDecimal apply = bal.min(remaining);
            BigDecimal newPaid = ((BigDecimal) inv.get("paid")).add(apply);
            String st = newPaid.compareTo((BigDecimal) inv.get("amount")) >= 0 ? "PAID" : "PARTIAL";
            db.update("update billing.invoice set paid = ?, status = ?, updated_at = now() where id = ?", newPaid, st, inv.get("id"));
            audit.record("Invoice", (UUID) inv.get("id"), "PAYMENT_APPLIED", (String) inv.get("status"), st, Map.of("amount", apply, "reference", ref));
            if ("DEPOSIT".equals(inv.get("kind")))
                db.update("update billing.deposit set held = held + ?, status = case when held + ? >= amount then 'HELD' else status end, updated_at = now() where lease_id = ?", apply, apply, inv.get("lease_id"));
            BigDecimal invTax = inv.get("tax_amount") == null ? BigDecimal.ZERO : (BigDecimal) inv.get("tax_amount");
            BigDecimal taxShare = invTax.signum() == 0 ? BigDecimal.ZERO : apply.multiply(invTax).divide((BigDecimal) inv.get("amount"), 2, RoundingMode.HALF_UP);
            allocations.add(Map.of("invoiceId", inv.get("id").toString(), "amount", apply, "kind", inv.get("kind").toString(), "tax", taxShare));
            remaining = remaining.subtract(apply);
        }
        db.update("""
                insert into billing.payment (id, org_id, property_id, owner_id, tenant_id, tenant_name, amount, method, method_last4, status, reference, allocations, source, fee_amount)
                values (?,?,?,?,?,?,?,?,?,'SUCCEEDED',?,?::jsonb,?,?)""", pid, first.get("org_id"), first.get("property_id"), first.get("owner_id"), tenantId,
                first.get("tenant_name"), amount, method, last4, ref, db.json(allocations), source, convFee);
        ledger(pctx, "PAYMENT", amount.negate(), "Payment " + ref, "PAYMENT", pid);
        events.publish("PaymentReceived", "Payment", pid, Map.of("tenantId", tenantId.toString(), "amount", amount.toString(), "reference", ref));
        notifier.toParty(tenantId, "Payment received", "We received your payment of $" + amount + (convFee.signum() > 0 ? " plus a $" + convFee + " processing fee" : "") + ". Reference " + ref + ".", "/payments");
        return Map.of("id", pid, "reference", ref, "status", "SUCCEEDED", "applied", allocations, "fee", convFee, "totalCharged", amount.add(convFee));
    }

    /** Returned-payment fee, limited by the state cap when one is set. Failures here never mask the declined payment. */
    private void chargeReturnedPaymentFee(UUID tenantId, String state, Map<String, Object> inv) {
        try {
            if (!config.flag("payments/chargeNsfFee", true)) return;
            if (db.value(Long.class, "select count(*) from billing.invoice where tenant_id = ? and kind = 'NSF_FEE' and status <> 'VOID' and created_at > now() - interval '1 day'", tenantId) > 0) return;
            BigDecimal fee = config.number("fees/nsfFee", "35");
            JsonNode cap = config.stateRule(state == null ? "DEFAULT" : state).path("nsfFeeCap");
            if (config.flag("payments/enforceStateFeeCaps", true) && cap.isNumber()) fee = fee.min(cap.decimalValue());
            if (fee.signum() <= 0) return;
            Map<String, Object> lease = leaseFor(tenantId);
            insertInvoice(lease, "NSF_FEE", "Returned payment fee", null, fee, LocalDate.now().plusDays(7));
        } catch (RuntimeException ignored) {
            // The declined payment is still reported to the payer; the fee can be added by hand.
        }
    }

    @Transactional(readOnly = true)
    public Map<String, Object> mine() {
        UUID t = UserContextHolder.require().partyId();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("invoices", invoices(null, t, null).stream().limit(24).toList());
        out.put("payments", payments(t));
        out.put("methods", db.list("select * from billing.payment_method where tenant_id = ? order by created_at", t));
        BigDecimal balance = db.value(BigDecimal.class, "select coalesce(sum(amount - paid), 0) from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL')", t);
        BigDecimal overdue = db.value(BigDecimal.class, "select coalesce(sum(amount - paid), 0) from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL') and due_date < current_date", t);
        out.put("balance", balance);
        out.put("overdue", overdue);
        out.put("nextDue", db.find("select id, description, due_date, (amount - paid) as balance from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL') order by due_date limit 1", t).orElse(null));
        return out;
    }

    @Transactional
    public Map<String, Object> addMethod(Map<String, Object> b) {
        UUID t = UserContextHolder.require().partyId();
        String type = Optional.ofNullable(Req.str(b, "type")).orElse("ACH").toUpperCase();
        String last4 = Req.required(b, "last4");
        if (!last4.matches("\\d{4}")) throw ApiException.badRequest("Enter the last 4 digits of the account or card.");
        boolean autopay = Req.bool(b, "autopay");
        if (!Req.bool(b, "consent")) throw ApiException.badRequest("Authorise PropertyOS to charge this " + ("ACH".equals(type) ? "bank account" : "card") + " before saving it.");
        if (autopay && !config.flag("payments/autopayEnabled", true)) throw ApiException.badRequest("Autopay isn't available right now.");
        if (autopay) db.update("update billing.payment_method set autopay = false where tenant_id = ?", t);
        UUID id = UUID.randomUUID();
        db.update("insert into billing.payment_method (id, org_id, tenant_id, type, last4, label, autopay, consent_at) values (?,?,?,?,?,?,?, now())",
                id, UserContextHolder.orgId(), t, type, last4, ("ACH".equals(type) ? "Bank account" : "Card") + " ending " + last4, autopay);
        return Map.of("id", id);
    }

    @Transactional
    public void setAutopay(UUID methodId, boolean on) {
        UUID t = UserContextHolder.require().partyId();
        if (on) db.update("update billing.payment_method set autopay = false where tenant_id = ?", t);
        if (db.update("update billing.payment_method set autopay = ? where id = ? and tenant_id = ?", on, methodId, t) == 0) throw ApiException.notFound("Payment method");
    }

    // ---------- Billing cycle ----------
    /** Generates upcoming rent invoices, charges late fees and runs autopay. Safe to run repeatedly. */
    @Transactional
    public Map<String, Object> runCycle() {
        JsonNode leasesNode = client.get(ServiceClient.Service.LEASING, "/internal/leasing/active-leases");
        int rentCreated = 0, lateFees = 0, autopay = 0;
        int daysBefore = config.number("invoicing.invoiceDaysBeforeDue", "5").intValue();
        int dueDay = config.number("invoicing.dueDay", "1").intValue();
        LocalDate today = LocalDate.now();
        YearMonth target = !today.plusDays(daysBefore).isBefore(today.withDayOfMonth(1).plusMonths(1)) ? YearMonth.from(today).plusMonths(1) : YearMonth.from(today);
        for (JsonNode ln : leasesNode) {
            Map<String, Object> lease = toMap(ln);
            UUID leaseId = UUID.fromString(ln.path("id").asText());
            LocalDate leaseEnd = LocalDate.parse(ln.path("end_date").asText());
            LocalDate due = target.atDay(Math.min(dueDay, target.lengthOfMonth()));
            if (due.isAfter(leaseEnd.plusMonths(1))) continue;
            Long exists = db.value(Long.class, "select count(*) from billing.invoice where lease_id = ? and kind = 'RENT' and period = ? and status <> 'VOID'", leaseId, target.toString());
            if (exists == 0) {
                insertInvoice(lease, "RENT", "Rent for " + target.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " " + target.getYear(),
                        target.toString(), new BigDecimal(ln.path("rent").asText()), due);
                rentCreated++;
            }
        }
        // Late fees on overdue rent past the grace period, once per invoice.
        for (Map<String, Object> inv : db.list("""
                select i.* from billing.invoice i
                where i.kind = 'RENT' and i.status in ('OPEN','PARTIAL') and i.due_date < current_date""")) {
            JsonNode lease = findLease(leasesNode, (UUID) inv.get("lease_id"));
            if (lease == null) continue;
            int grace = lease.path("terms").path("lateFeeGraceDays").asInt(5);
            if (config.flag("payments/enforceStateFeeCaps", true)) grace = Math.max(grace, config.stateRule(lease.path("state").asText("DEFAULT")).path("lateFeeGraceDays").asInt(0));
            if (!LocalDate.parse(inv.get("due_date").toString()).plusDays(grace).isBefore(today)) continue;
            Long has = db.value(Long.class, "select count(*) from billing.invoice where lease_id = ? and kind = 'LATE_FEE' and period = ? and status <> 'VOID'", inv.get("lease_id"), inv.get("period"));
            if (has > 0) continue;
            BigDecimal fee = new BigDecimal(lease.path("terms").path("lateFee").asText("0"));
            BigDecimal cap = lateFeeCap(lease.path("state").asText("DEFAULT"), new BigDecimal(lease.path("rent").asText("0")));
            if (cap != null) fee = fee.min(cap);
            if (fee.signum() <= 0) continue;
            insertInvoice(toMap(lease), "LATE_FEE", "Late fee for " + inv.get("description"), (String) inv.get("period"), fee, today.plusDays(7));
            events.publish("LateFeeCharged", "Invoice", (UUID) inv.get("id"), Map.of("tenantId", inv.get("tenant_id").toString(), "fee", fee.toString()));
            lateFees++;
        }
        // Autopay for invoices due today or earlier.
        for (Map<String, Object> m : db.list("select * from billing.payment_method where autopay")) {
            UUID t = (UUID) m.get("tenant_id");
            BigDecimal due = db.value(BigDecimal.class, "select coalesce(sum(amount - paid), 0) from billing.invoice where tenant_id = ? and status in ('OPEN','PARTIAL') and due_date <= current_date", t);
            if (due.signum() > 0) {
                try {
                    takePayment(t, due, null, (String) m.get("type"), (String) m.get("last4"), "AUTOPAY");
                    autopay++;
                } catch (ApiException e) {
                    // Declined or nothing due: the failure is recorded and the tenant notified.
                }
            }
        }
        return Map.of("rentInvoicesCreated", rentCreated, "lateFeesCharged", lateFees, "autopayCollected", autopay, "period", target.toString());
    }

    private JsonNode findLease(JsonNode leases, UUID id) {
        for (JsonNode n : leases) if (n.path("id").asText().equals(String.valueOf(id))) return n;
        return null;
    }

    private Map<String, Object> toMap(JsonNode ln) {
        Map<String, Object> m = new HashMap<>();
        for (String k : List.of("id", "org_id", "property_id", "owner_id", "tenant_id")) m.put(k, UUID.fromString(ln.path(k).asText()));
        m.put("tenant_name", ln.path("tenant_name").asText());
        m.put("unit_label", ln.path("unit_label").asText());
        m.put("property_name", ln.path("property_name").asText());
        if (ln.hasNonNull("state")) m.put("state", ln.path("state").asText());
        if (ln.hasNonNull("rent")) m.put("rent", new BigDecimal(ln.path("rent").asText()));
        return m;
    }

    private Map<String, Object> leaseFor(UUID tenantId) {
        JsonNode leases = client.get(ServiceClient.Service.LEASING, "/internal/leasing/active-leases");
        for (JsonNode n : leases) if (n.path("tenant_id").asText().equals(tenantId.toString())) return toMap(n);
        throw ApiException.notFound("An active lease for this tenant");
    }
}
