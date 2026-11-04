package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.SqlPaging;
import com.propertyos.common.rls.Tx;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.*;

/**
 * What each company pays PropertyOS: a monthly subscription invoice per company (units managed times the plan's price per unit, never
 * less than the plan's monthly minimum), the payments made against it, and the figures the operator needs to run the business:
 * billed, collected, outstanding, overdue, and who owes what.
 *
 * <p>The operator sees amounts, dates and statuses only. A company sees only its own invoices.</p>
 */
@Service
public class PlatformBilling {
    private static final Set<String> METHODS = Set.of("CARD", "ACH", "WIRE", "CHEQUE", "CREDIT");
    private static final Set<String> SELF_SERVICE = Set.of("CARD", "ACH");
    /** Days between an invoice being made and being due. */
    static final int TERMS_DAYS = 14;

    private final JdbcTemplate jdbc;
    private final Tx tx;
    private final ServiceClient client;
    private final Notifier notifier;
    private final AuditService audit;
    private final EventPublisher events;

    /** Whether the nightly timer bills companies by itself. On in demo environments; in production the operator turns it on deliberately. */
    private final boolean automatic;

    public PlatformBilling(JdbcTemplate jdbc, Tx tx, ServiceClient client, Notifier notifier, AuditService audit, EventPublisher events,
                           @Value("${propertyos.platform-billing.auto:${propertyos.demo.enabled:true}}") boolean automatic) {
        this.events = events;
        this.automatic = automatic;
        this.jdbc = jdbc;
        this.tx = tx;
        this.client = client;
        this.notifier = notifier;
        this.audit = audit;
    }

    private static BigDecimal money(Object v, String what) {
        try {
            BigDecimal m = new BigDecimal(String.valueOf(v)).setScale(2, RoundingMode.HALF_UP);
            return m;
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Enter " + what + " as a number, for example 125.00.");
        }
    }

    // ----------------------------------------------------------------------------------------------- the monthly run

    /** Runs every day (when automatic billing is on): makes this month's invoices, and tells the operator about invoices that have gone overdue. */
    @Scheduled(cron = "${propertyos.platform-billing.cron:0 30 2 * * *}", zone = "America/Chicago")
    public void daily() {
        if (!automatic) return; // billing real customers is the operator's decision: set PROPERTYOS_PLATFORM_BILLING_AUTO=true, or press "Run billing now"
        UserContextHolder.runAsSystem(null, () -> runCycle(null));
        UserContextHolder.runAsSystem(DemoData.PLATFORM.id(), () -> { flagOverdue(); return null; });
    }

    /**
     * Makes the subscription invoice for the month of {@code asOf} (today when null) for every active company that does not have one yet.
     * Safe to run again: a company is never invoiced twice for the same month.
     *
     * @return how many invoices were made and how many companies already had one
     */
    public Map<String, Object> runCycle(LocalDate asOf) {
        LocalDate day = asOf == null ? LocalDate.now() : asOf;
        YearMonth month = YearMonth.from(day);
        String period = month.toString();
        List<Map<String, Object>> orgs = tx.read(() -> jdbc.queryForList("""
                select o.id, o.name, o.plan, p.price_per_unit, p.minimum_monthly
                from identity.organization o join identity.plan p on p.plan_key = o.plan
                where o.status = 'ACTIVE' and o.id <> ? order by o.created_at""", DemoData.PLATFORM.id()));
        int created = 0, had = 0;
        for (Map<String, Object> o : orgs) {
            UUID org = (UUID) o.get("id");
            BigDecimal price = (BigDecimal) o.get("price_per_unit"), min = (BigDecimal) o.get("minimum_monthly");
            long units = unitsOf(org);
            BigDecimal amount = min.max(price.multiply(BigDecimal.valueOf(units))).setScale(2, RoundingMode.HALF_UP);
            if (amount.signum() <= 0) continue;
            String description = "PropertyOS " + o.get("plan") + " plan, " + month.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + " " + month.getYear()
                    + ": " + units + (units == 1 ? " unit" : " units") + " at $" + price.toPlainString() + " (minimum $" + min.toPlainString() + ")";
            UUID id = UUID.randomUUID();
            LocalDate due = day.withDayOfMonth(1).plusDays(TERMS_DAYS - 1L);
            if (due.isBefore(day)) due = day.plusDays(TERMS_DAYS);
            LocalDate dueDate = due;
            Boolean made = tx.write(() -> {
                String number = "PO-" + period.replace("-", "") + "-" + String.format("%04d", jdbc.queryForObject("select nextval('identity.platform_invoice_seq')", Long.class));
                return jdbc.update("""
                        insert into identity.platform_invoice (id, org_id, number, period, kind, plan, units, unit_price, minimum, description, amount, due_date, created_by)
                        values (?,?,?,?, 'SUBSCRIPTION', ?,?,?,?,?,?,?, 'Monthly run')
                        on conflict (org_id, period) where kind = 'SUBSCRIPTION' and status <> 'VOID' do nothing""",
                        id, org, number, period, o.get("plan"), (int) units, price, min, description, amount, dueDate) > 0;
            });
            if (Boolean.TRUE.equals(made)) {
                created++;
                tellCompany(org, "Your PropertyOS invoice is ready", "$" + amount + " for " + month.getMonth().getDisplayName(TextStyle.FULL, Locale.US) + ", due " + dueDate + ".", "invoice-" + id);
            } else had++;
        }
        return Map.of("period", period, "created", created, "alreadyInvoiced", had, "companies", orgs.size());
    }

    private long unitsOf(UUID org) {
        try {
            JsonNode n = UserContextHolder.runAsSystem(org, () -> client.get(ServiceClient.Service.LEASING, "/internal/leasing/unit-count"));
            return n == null ? 0 : n.path("units").asLong(0);
        } catch (RuntimeException e) {
            return 0; // the minimum still applies when the count cannot be read
        }
    }

    private void tellCompany(UUID org, String title, String body, String dedupe) {
        try {
            UserContextHolder.runAsSystem(org, () -> { notifier.sendAlert("ROLE", "ORG_ADMIN", title, body, "/admin/subscription", "NORMAL", "GENERAL", dedupe); return null; });
        } catch (RuntimeException ignored) {
            // an alert that cannot be sent must not stop billing
        }
    }

    private void flagOverdue() {
        for (Map<String, Object> r : tx.read(() -> jdbc.queryForList("""
                select i.id, i.number, i.amount - i.paid as balance, i.due_date, o.name from identity.platform_invoice i join identity.organization o on o.id = i.org_id
                where i.status in ('OPEN','PARTIAL') and i.due_date < current_date and o.status = 'ACTIVE'"""))) {
            notifier.sendAlert("ROLE", "PLATFORM_ADMIN", "Subscription overdue: " + r.get("name"), "Invoice " + r.get("number") + " ($" + r.get("balance") + ") was due " + r.get("due_date") + ".",
                    "/admin/payments", "HIGH", "GENERAL", "overdue-" + r.get("id"));
        }
    }

    // ----------------------------------------------------------------------------------------------- reading

    /** The figures for the operator's payments page. */
    public Map<String, Object> summary() {
        Map<String, Object> out = tx.read(() -> {
            Map<String, Object> m = new LinkedHashMap<>();
            String period = YearMonth.now().toString();
            m.put("period", period);
            // Money from listing boosts, which are one-off invoices of kind BOOST, counted when the payment succeeded.
            m.put("boostRevenueThisMonth", num("""
                    select coalesce(sum(p.amount),0) from identity.platform_payment p join identity.platform_invoice i on i.id = p.invoice_id
                    where p.status = 'SUCCEEDED' and i.kind = 'BOOST' and to_char(p.created_at, 'YYYY-MM') = ?""", period));
            m.put("billedThisMonth", num("select coalesce(sum(amount),0) from identity.platform_invoice where status <> 'VOID' and to_char(created_at, 'YYYY-MM') = ?", period));
            m.put("collectedThisMonth", num("select coalesce(sum(amount),0) from identity.platform_payment where status = 'SUCCEEDED' and to_char(created_at, 'YYYY-MM') = ?", period));
            m.put("collected30d", num("select coalesce(sum(amount),0) from identity.platform_payment where status = 'SUCCEEDED' and created_at > now() - interval '30 days'"));
            m.put("outstanding", num("select coalesce(sum(amount - paid),0) from identity.platform_invoice where status in ('OPEN','PARTIAL')"));
            m.put("overdue", num("select coalesce(sum(amount - paid),0) from identity.platform_invoice where status in ('OPEN','PARTIAL') and due_date < current_date"));
            m.put("overdueCompanies", jdbc.queryForObject("select count(distinct org_id) from identity.platform_invoice where status in ('OPEN','PARTIAL') and due_date < current_date", Long.class));
            m.put("failed30d", jdbc.queryForObject("select count(*) from identity.platform_payment where status = 'FAILED' and created_at > now() - interval '30 days'", Long.class));
            m.put("payingCompanies", jdbc.queryForObject("select count(distinct org_id) from identity.platform_invoice where status <> 'VOID' and period = ?", Long.class, period));
            m.put("byPlan", jdbc.queryForList("select plan, count(*) as companies, coalesce(sum(amount),0) as billed from identity.platform_invoice where status <> 'VOID' and period = ? and kind = 'SUBSCRIPTION' group by plan order by plan", period));
            m.put("trend", jdbc.queryForList("""
                    select to_char(d, 'YYYY-MM') as month,
                           (select coalesce(sum(amount),0) from identity.platform_invoice where status <> 'VOID' and to_char(created_at, 'YYYY-MM') = to_char(d, 'YYYY-MM')) as billed,
                           (select coalesce(sum(amount),0) from identity.platform_payment where status = 'SUCCEEDED' and to_char(created_at, 'YYYY-MM') = to_char(d, 'YYYY-MM')) as collected
                    from generate_series(date_trunc('month', now()) - interval '5 months', date_trunc('month', now()), interval '1 month') d order by d"""));
            m.put("topOwing", jdbc.queryForList("""
                    select o.id as org_id, o.name, sum(i.amount - i.paid) as owing, min(i.due_date) as oldest_due
                    from identity.platform_invoice i join identity.organization o on o.id = i.org_id where i.status in ('OPEN','PARTIAL')
                    group by o.id, o.name order by owing desc limit 5"""));
            return m;
        });
        // How many boosts are running now is leasing's to say; the page still works if it cannot be reached.
        out.put("activeBoosts", activeBoosts());
        return out;
    }

    private long activeBoosts() {
        try {
            JsonNode n = UserContextHolder.runAsSystem(null, () -> client.get(ServiceClient.Service.LEASING, "/internal/leasing/boosts/summary"));
            return n == null ? 0 : n.path("active").asLong(0);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private BigDecimal num(String sql, Object... args) {
        return jdbc.queryForObject(sql, BigDecimal.class, args);
    }

    /** One page of invoices, newest first. {@code orgId} limits it to one company. */
    public List<Map<String, Object>> invoices(UUID orgId) {
        return tx.read(() -> SqlPaging.page(jdbc, """
                select i.*, (i.amount - i.paid) as balance, o.name as org_name,
                       case when i.status in ('OPEN','PARTIAL') and i.due_date < current_date then true else false end as overdue
                from identity.platform_invoice i join identity.organization o on o.id = i.org_id""" + (orgId == null ? "" : " where i.org_id = ?") + " order by i.created_at desc, i.number desc",
                orgId == null ? new Object[0] : new Object[]{orgId}));
    }

    /** One page of payments, newest first. */
    public List<Map<String, Object>> payments(UUID orgId) {
        return tx.read(() -> SqlPaging.page(jdbc, """
                select p.*, i.number as invoice_number, o.name as org_name from identity.platform_payment p
                join identity.platform_invoice i on i.id = p.invoice_id join identity.organization o on o.id = p.org_id""" + (orgId == null ? "" : " where p.org_id = ?") + " order by p.created_at desc",
                orgId == null ? new Object[0] : new Object[]{orgId}));
    }

    /** One invoice with its payments. A company may only open its own ({@code onlyOrg}). */
    public Map<String, Object> invoice(UUID id, UUID onlyOrg) {
        return tx.read(() -> {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    select i.*, (i.amount - i.paid) as balance, o.name as org_name from identity.platform_invoice i join identity.organization o on o.id = i.org_id where i.id = ?""", id);
            if (rows.isEmpty() || (onlyOrg != null && !onlyOrg.equals(rows.get(0).get("org_id")))) throw ApiException.notFound("Invoice");
            Map<String, Object> inv = new LinkedHashMap<>(rows.get(0));
            inv.put("payments", jdbc.queryForList("select * from identity.platform_payment where invoice_id = ? order by created_at desc", id));
            return inv;
        });
    }

    /** Everything about one company's account with PropertyOS: what it owes, what it has paid, and every invoice. */
    public Map<String, Object> ledger(UUID orgId) {
        return tx.read(() -> {
            List<Map<String, Object>> org = jdbc.queryForList("select id, name, plan, status from identity.organization where id = ?", orgId);
            if (org.isEmpty()) throw ApiException.notFound("Company");
            Map<String, Object> out = new LinkedHashMap<>(org.get(0));
            out.put("billed", num("select coalesce(sum(amount),0) from identity.platform_invoice where org_id = ? and status <> 'VOID'", orgId));
            out.put("paid", num("select coalesce(sum(amount),0) from identity.platform_payment where org_id = ? and status = 'SUCCEEDED'", orgId));
            out.put("balance", num("select coalesce(sum(amount - paid),0) from identity.platform_invoice where org_id = ? and status in ('OPEN','PARTIAL')", orgId));
            out.put("overdue", num("select coalesce(sum(amount - paid),0) from identity.platform_invoice where org_id = ? and status in ('OPEN','PARTIAL') and due_date < current_date", orgId));
            out.put("invoices", jdbc.queryForList("select i.*, (i.amount - i.paid) as balance from identity.platform_invoice i where org_id = ? order by created_at desc limit 50", orgId));
            out.put("payments", jdbc.queryForList("select p.*, i.number as invoice_number from identity.platform_payment p join identity.platform_invoice i on i.id = p.invoice_id where p.org_id = ? order by p.created_at desc limit 50", orgId));
            return out;
        });
    }

    /** What a company sees about its own subscription: plan, price, what it owes and what is coming. */
    public Map<String, Object> subscription(UUID orgId) {
        Map<String, Object> out = new LinkedHashMap<>(ledger(orgId));
        out.putAll(tx.read(() -> jdbc.queryForMap("""
                select p.name as plan_name, p.price_per_unit, p.minimum_monthly from identity.organization o join identity.plan p on p.plan_key = o.plan where o.id = ?""", orgId)));
        long units = unitsOf(orgId);
        BigDecimal price = (BigDecimal) out.get("price_per_unit"), min = (BigDecimal) out.get("minimum_monthly");
        out.put("units", units);
        out.put("estimatedNextInvoice", min.max(price.multiply(BigDecimal.valueOf(units))).setScale(2, RoundingMode.HALF_UP));
        return out;
    }

    // ----------------------------------------------------------------------------------------------- writing

    /** A one-off charge for a company (set-up help, a credit-back as a negative is not allowed: use a CREDIT payment instead). */
    public Map<String, Object> createCharge(Map<String, Object> b) {
        UUID org = Req.requiredUuid(b, "orgId");
        BigDecimal amount = money(Req.required(b, "amount"), "the amount");
        if (amount.signum() <= 0) throw ApiException.badRequest("The amount must be more than zero. To credit a company, record a Credit payment on its invoice.");
        String description = Req.required(b, "description");
        if (description.length() > 300) throw ApiException.badRequest("The description is too long (300 characters at most).");
        LocalDate due = Optional.ofNullable(Req.date(b, "dueDate")).orElse(LocalDate.now().plusDays(TERMS_DAYS));
        UUID id = UUID.randomUUID();
        tx.run(() -> {
            if (jdbc.queryForObject("select count(*) from identity.organization where id = ? and status <> 'DELETED'", Long.class, org) == 0) throw ApiException.notFound("Company");
            String number = "PO-" + YearMonth.now().toString().replace("-", "") + "-" + String.format("%04d", jdbc.queryForObject("select nextval('identity.platform_invoice_seq')", Long.class));
            jdbc.update("insert into identity.platform_invoice (id, org_id, number, kind, description, amount, due_date, created_by) values (?,?,?,'ONE_OFF',?,?,?,?)",
                    id, org, number, description, amount, due, UserContextHolder.require().name());
            audit.record("PlatformInvoice", id, "PLATFORM_INVOICE_CREATED", null, "OPEN", Map.of("org", org.toString(), "amount", amount.toString(), "description", description));
        });
        tellCompany(org, "New PropertyOS charge", description + ": $" + amount + ", due " + due + ".", "invoice-" + id);
        return invoice(id, null);
    }

    /**
     * Service-to-service: raises a one-off invoice for a company on another service's behalf (a listing boost). The company then pays it the
     * usual way from its Subscription page. Unlike {@link #createCharge} it needs no operator, and it is always for the stated company.
     *
     * @param kind short invoice kind, for example BOOST
     * @return the new invoice's id, number and amount
     */
    public Map<String, Object> raiseInvoice(UUID org, String kind, BigDecimal amount, String description, int dueInDays) {
        if (amount.signum() <= 0) throw ApiException.badRequest("The amount must be more than zero.");
        UUID id = UUID.randomUUID();
        String[] number = new String[1];
        tx.run(() -> {
            if (jdbc.queryForObject("select count(*) from identity.organization where id = ? and status = 'ACTIVE'", Long.class, org) == 0) throw ApiException.notFound("Company");
            number[0] = "PO-" + YearMonth.now().toString().replace("-", "") + "-" + String.format("%04d", jdbc.queryForObject("select nextval('identity.platform_invoice_seq')", Long.class));
            jdbc.update("insert into identity.platform_invoice (id, org_id, number, kind, description, amount, due_date, created_by) values (?,?,?,?,?,?,?,?)",
                    id, org, number[0], kind, description, amount, LocalDate.now().plusDays(dueInDays), "Listing boost");
            audit.record("PlatformInvoice", id, "PLATFORM_INVOICE_CREATED", null, "OPEN", Map.of("org", org.toString(), "amount", amount.toString(), "description", description, "kind", kind));
        });
        tellCompany(org, "New PropertyOS charge", description + ": $" + amount + ". Pay it from Subscription.", "invoice-" + id);
        return Map.of("id", id, "number", number[0], "amount", amount);
    }

    /** Cancels an invoice that was made in error. A reason is required and an invoice with payments cannot be cancelled. */
    public Map<String, Object> voidInvoice(UUID id, String reason) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("A reason is required to void an invoice.");
        tx.run(() -> {
            List<Map<String, Object>> r = jdbc.queryForList("select status, paid from identity.platform_invoice where id = ?", id);
            if (r.isEmpty()) throw ApiException.notFound("Invoice");
            if (((BigDecimal) r.get(0).get("paid")).signum() > 0) throw ApiException.conflict("This invoice has payments against it. Record a Credit payment instead of voiding it.");
            if ("VOID".equals(r.get(0).get("status"))) return;
            jdbc.update("update identity.platform_invoice set status = 'VOID', void_reason = ?, updated_at = now() where id = ?", reason, id);
            audit.record("PlatformInvoice", id, "PLATFORM_INVOICE_VOIDED", (String) r.get(0).get("status"), "VOID", Map.of("reason", reason));
        });
        return invoice(id, null);
    }

    /**
     * Records a payment against an invoice.
     *
     * @param onlyOrg set when a company pays its own invoice (card or bank only; a card ending 0002 is declined in the demo gateway)
     * @throws ApiException (409) when the payment is declined, (400) for a bad amount or method
     */
    public Map<String, Object> pay(UUID invoiceId, Map<String, Object> b, UUID onlyOrg) {
        String method = Optional.ofNullable(Req.str(b, "method")).orElse("CARD").toUpperCase();
        if (!METHODS.contains(method)) throw ApiException.badRequest("Choose how it was paid: card, bank transfer, wire, cheque or credit.");
        if (onlyOrg != null && !SELF_SERVICE.contains(method)) throw ApiException.badRequest("Pay by card or bank transfer here. For anything else, contact PropertyOS.");
        String last4 = Req.str(b, "last4");
        if (last4 != null && !last4.matches("\\d{4}")) throw ApiException.badRequest("Enter only the last 4 digits of the card or account.");
        String reference = Optional.ofNullable(Req.str(b, "reference")).orElse("PAY-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase());
        String note = Req.str(b, "note");
        String by = UserContextHolder.require().name();
        Map<String, Object> inv = invoice(invoiceId, onlyOrg);
        if ("VOID".equals(inv.get("status"))) throw ApiException.conflict("This invoice was cancelled.");
        BigDecimal balance = (BigDecimal) inv.get("balance");
        if (balance.signum() <= 0) throw ApiException.conflict("This invoice is already paid.");
        BigDecimal amount = b.get("amount") == null ? balance : money(b.get("amount"), "the amount");
        if (amount.signum() <= 0) throw ApiException.badRequest("The amount must be more than zero.");
        if (amount.compareTo(balance) > 0) throw ApiException.badRequest("That's more than the $" + balance + " owing. Enter an amount up to the balance.");
        UUID org = (UUID) inv.get("org_id");
        boolean declined = SELF_SERVICE.contains(method) && last4 != null && last4.endsWith("0002");
        UUID pid = UUID.randomUUID();
        tx.run(() -> {
            jdbc.update("insert into identity.platform_payment (id, org_id, invoice_id, amount, method, last4, reference, status, failure_reason, note, recorded_by) values (?,?,?,?,?,?,?,?,?,?,?)",
                    pid, org, invoiceId, amount, method, last4, reference, declined ? "FAILED" : "SUCCEEDED", declined ? "Insufficient funds" : null, note, by);
            if (!declined) {
                BigDecimal paid = ((BigDecimal) inv.get("paid")).add(amount);
                boolean full = paid.compareTo((BigDecimal) inv.get("amount")) >= 0;
                jdbc.update("update identity.platform_invoice set paid = ?, status = ?, updated_at = now() where id = ?", paid, full ? "PAID" : "PARTIAL", invoiceId);
                // Whatever was bought with this invoice (a listing boost) starts only now. A declined payment never reaches here, so nothing starts.
                if (full) events.publish(org, "PlatformInvoicePaid", "PlatformInvoice", invoiceId, Map.of("invoiceId", invoiceId.toString(), "kind", String.valueOf(inv.get("kind")), "number", String.valueOf(inv.get("number"))));
            }
            audit.record("PlatformInvoice", invoiceId, declined ? "PLATFORM_PAYMENT_FAILED" : "PLATFORM_PAYMENT_RECORDED", (String) inv.get("status"), null,
                    Map.of("amount", amount.toString(), "method", method, "reference", reference, "by", by));
        });
        if (declined) {
            UserContextHolder.runAsSystem(DemoData.PLATFORM.id(), () -> { notifier.sendAlert("ROLE", "PLATFORM_ADMIN", "Subscription payment failed: " + inv.get("org_name"),
                    "$" + amount + " on invoice " + inv.get("number") + " was declined.", "/admin/payments", "HIGH", "GENERAL", "failed-" + pid); return null; });
            throw ApiException.conflict("The payment was declined (insufficient funds). Reference " + reference + ". No charge was made.");
        }
        tellCompany(org, "Payment received, thank you", "$" + amount + " towards PropertyOS invoice " + inv.get("number") + ". Reference " + reference + ".", "paid-" + pid);
        return invoice(invoiceId, null);
    }
}
