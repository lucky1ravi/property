package com.propertyos.billing.service;

import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Collections analytics: KPIs, aging, payment-risk scoring and the daily collections worklist. */
@Service
public class ReportingService {
    private final Db db;
    private final Notifier notifier;
    private final EventPublisher events;
    private final ConfigClient config;

    public ReportingService(Db db, Notifier notifier, EventPublisher events, ConfigClient config) {
        this.config = config;
        this.db = db;
        this.notifier = notifier;
        this.events = events;
    }

    /**
     * Returns billed and collected totals for the month, outstanding balance and related figures.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> summary() {
        Map<String, Object> s = new LinkedHashMap<>(db.one("Summary", """
                select coalesce(sum(amount) filter (where status <> 'VOID' and date_trunc('month', due_date) = date_trunc('month', current_date)), 0) as billed_this_month,
                       coalesce(sum(paid)   filter (where status <> 'VOID' and date_trunc('month', due_date) = date_trunc('month', current_date)), 0) as collected_this_month,
                       coalesce(sum(amount - paid) filter (where status in ('OPEN','PARTIAL') and due_date < current_date), 0) as overdue_total,
                       count(*) filter (where status in ('OPEN','PARTIAL') and due_date < current_date) as overdue_invoices,
                       count(distinct tenant_id) filter (where status in ('OPEN','PARTIAL') and due_date < current_date) as delinquent_tenants,
                       coalesce(sum(amount - paid) filter (where status in ('OPEN','PARTIAL')), 0) as receivable_total
                from billing.invoice"""));
        BigDecimal billed = (BigDecimal) s.get("billed_this_month");
        s.put("collection_rate", billed.signum() == 0 ? 100 : ((BigDecimal) s.get("collected_this_month")).multiply(BigDecimal.valueOf(100)).divide(billed, 1, RoundingMode.HALF_UP));
        s.put("failed_payments_30d", db.value(Long.class, "select count(*) from billing.payment where status = 'FAILED' and created_at > now() - interval '30 days'"));
        s.put("trend", db.list("""
                select to_char(date_trunc('month', due_date), 'YYYY-MM') as month, sum(amount) filter (where status <> 'VOID') as billed, sum(paid) filter (where status <> 'VOID') as collected
                from billing.invoice where due_date > current_date - interval '6 months' and kind in ('RENT','LATE_FEE') group by 1 order by 1"""));
        return s;
    }

    /**
     * Returns outstanding balances in 1-30, 31-60 and older buckets.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> aging() {
        Map<String, Object> a = db.one("Aging", """
                select coalesce(sum(amount - paid) filter (where current_date - due_date between 1 and 30), 0) as d1_30,
                       coalesce(sum(amount - paid) filter (where current_date - due_date between 31 and 60), 0) as d31_60,
                       coalesce(sum(amount - paid) filter (where current_date - due_date > 60), 0) as d61_plus,
                       coalesce(sum(amount - paid) filter (where current_date - due_date <= 0), 0) as current_due
                from billing.invoice where status in ('OPEN','PARTIAL')""");
        return a;
    }

    /**
     * Ranked collections worklist: who owes what, a payment-risk score from their history, and the recommended next step.
     * Risk is rules-based and explainable: late-paid invoices, current overdue, failed payments.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> collections() {
        List<Map<String, Object>> rows = db.list("""
                select tenant_id, max(tenant_name) as tenant_name, max(unit_label) as unit_label,
                       sum(amount - paid) as balance, max(current_date - due_date) as oldest_days,
                       count(*) as open_invoices
                from billing.invoice where status in ('OPEN','PARTIAL') and due_date < current_date
                group by tenant_id order by sum(amount - paid) desc""");
        for (Map<String, Object> r : rows) {
            Object t = r.get("tenant_id");
            Long failed = db.value(Long.class, "select count(*) from billing.payment where tenant_id = ? and status = 'FAILED' and created_at > now() - interval '90 days'", t);
            Long lateHistory = db.value(Long.class, "select count(*) from billing.invoice where tenant_id = ? and kind = 'LATE_FEE' and status <> 'VOID'", t);
            int days = ((Number) r.get("oldest_days")).intValue();
            int risk = Math.min(100, days * 2 + lateHistory.intValue() * 12 + failed.intValue() * 15);
            r.put("risk_score", risk);
            r.put("risk_level", risk >= 60 ? "HIGH" : risk >= 30 ? "MEDIUM" : "LOW");
            String next = days > 30 ? "Escalate: call the tenant and offer a payment plan, then start the notice process your state requires."
                    : days > 10 ? "Call the tenant today and offer a payment plan." : "Send a friendly reminder.";
            r.put("recommendation", next);
            r.put("reasons", List.of(days + " days past due", lateHistory + " late fee(s) in history", failed + " failed payment(s) in 90 days"));
        }
        rows.sort((x, y) -> Integer.compare((Integer) y.get("risk_score"), (Integer) x.get("risk_score")));
        return rows;
    }

    /** Sends a payment reminder to one tenant, or to everyone overdue when tenantId is null. */
    @Transactional
    public Map<String, Object> remind(UUID tenantId) {
        List<Map<String, Object>> targets = collections().stream().filter(r -> tenantId == null || tenantId.equals(r.get("tenant_id"))).toList();
        if (targets.isEmpty()) throw ApiException.notFound("An overdue balance for this tenant");
        for (Map<String, Object> t : targets) {
            notifier.toParty((UUID) t.get("tenant_id"), "Rent reminder", "Your account has a past-due balance of $" + t.get("balance") + ". Pay in the resident portal to avoid more fees.", "/payments");
            events.publish("ReminderSent", "Tenant", (UUID) t.get("tenant_id"), Map.of("balance", t.get("balance").toString()));
        }
        return Map.of("sent", targets.size());
    }

    // ---------- Tax and compliance ----------
    /** Sales or rental tax billed and collected, by month and state. Collected tax is the share of each invoice that has been paid. */
    @Transactional(readOnly = true)
    public Map<String, Object> taxSummary(int year) {
        List<Map<String, Object>> rows = db.list("""
                select to_char(created_at, 'YYYY-MM') as month, coalesce(state, 'N/A') as state,
                       sum(subtotal) filter (where tax_amount > 0) as taxable_base,
                       sum(subtotal) filter (where tax_amount = 0 and kind <> 'DEPOSIT') as non_taxable,
                       sum(tax_amount) as tax_invoiced,
                       round(sum(tax_amount * least(paid / nullif(amount, 0), 1)), 2) as tax_collected,
                       max(tax_rate) as rate
                from billing.invoice where status <> 'VOID' and extract(year from created_at) = ?
                group by 1, 2 order by 1 desc, 2""", year);
        BigDecimal invoiced = BigDecimal.ZERO, collected = BigDecimal.ZERO;
        for (Map<String, Object> r : rows) {
            invoiced = invoiced.add(r.get("tax_invoiced") == null ? BigDecimal.ZERO : (BigDecimal) r.get("tax_invoiced"));
            collected = collected.add(r.get("tax_collected") == null ? BigDecimal.ZERO : (BigDecimal) r.get("tax_collected"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("year", year);
        out.put("rows", rows);
        out.put("taxInvoiced", invoiced);
        out.put("taxCollected", collected);
        out.put("taxEnabled", config.flag("payments/tax/enabled", true));
        return out;
    }

    /** Preparation worksheet for IRS Form 1099-MISC (rents paid to owners). It is not a filed return. */
    @Transactional(readOnly = true)
    public Map<String, Object> owner1099(int year) {
        BigDecimal threshold = config.number("payments/reporting/form1099Threshold", "2000");
        BigDecimal bw = config.number("payments/reporting/backupWithholdingPercent", "24");
        List<Map<String, Object>> rows = db.list("""
                select s.owner_id, max(s.owner_name) as owner_name, count(*) as statements,
                       sum(s.rent_collected + s.fees_collected) as gross_collected, sum(s.net_payout) as net_paid,
                       max(p.legal_name) as legal_name, max(p.tin_type) as tin_type, max(p.tin_last4) as tin_last4, max(p.w9_received_at) as w9_received_at
                from billing.owner_statement s left join billing.owner_tax_profile p on p.owner_id = s.owner_id
                where s.status in ('PUBLISHED','PAID') and s.period like ?
                group by s.owner_id order by sum(s.net_payout) desc""", year + "-%");
        int missing = 0;
        for (Map<String, Object> r : rows) {
            boolean reportable = ((BigDecimal) r.get("gross_collected")).compareTo(threshold) >= 0;
            boolean w9 = r.get("w9_received_at") != null;
            r.put("reportable", reportable);
            r.put("w9_on_file", w9);
            r.put("backup_withholding_due", reportable && !w9 ? ((BigDecimal) r.get("net_paid")).multiply(bw).divide(BigDecimal.valueOf(100), 2, java.math.RoundingMode.HALF_UP) : BigDecimal.ZERO);
            if (reportable && !w9) missing++;
        }
        // Owners with no published statement yet still need a W-9 before their first payout.
        return Map.of("year", year, "threshold", threshold, "backupWithholdingPercent", bw, "owners", rows, "missingW9", missing);
    }

    /**
     * Records whether an owner's W-9 was received and the last four digits of their tax id. The full number is never stored.
     *
     * @throws ApiException (400) unless the digits are exactly four
     */
    @Transactional
    public Map<String, Object> saveW9(UUID ownerId, Map<String, Object> b) {
        String tin = com.propertyos.common.web.Req.str(b, "tinLast4");
        if (tin != null && !tin.matches("\\d{4}")) throw ApiException.badRequest("Enter only the last 4 digits of the SSN or EIN. The full number is never stored here.");
        boolean received = com.propertyos.common.web.Req.bool(b, "w9Received");
        UUID org = com.propertyos.common.security.UserContextHolder.orgId();
        db.update("""
                insert into billing.owner_tax_profile (owner_id, org_id, legal_name, tin_type, tin_last4, w9_received_at, updated_by)
                values (?,?,?,?,?,?,?)
                on conflict (owner_id) do update set legal_name = excluded.legal_name, tin_type = excluded.tin_type, tin_last4 = excluded.tin_last4,
                    w9_received_at = excluded.w9_received_at, updated_by = excluded.updated_by, updated_at = now()""",
                ownerId, org, com.propertyos.common.web.Req.str(b, "legalName"), com.propertyos.common.web.Req.str(b, "tinType"), tin,
                received ? java.time.LocalDate.now() : null, com.propertyos.common.security.UserContextHolder.require().name());
        return Map.of("ownerId", ownerId, "w9Received", received);
    }
}
