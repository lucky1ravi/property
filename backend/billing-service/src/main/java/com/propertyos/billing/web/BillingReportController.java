package com.propertyos.billing.web;

import com.propertyos.common.rls.Db;
import com.propertyos.common.rls.ReportRows;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Money reports: who owes what and for how long, what each property earned and spent month by month, how payments are going, which
 * fees were charged, and every transaction behind a figure. Each answers {@code {summary, rows, total, page, size}} with the usual
 * paging, sorting, search and {@code f.<column>} filters. Owners see their own properties and property staff theirs, by row-level security.
 */
@RestController
@RequestMapping("/api/billing/reports")
public class BillingReportController {
    private final Db db;

    public BillingReportController(Db db) {
        this.db = db;
    }

    private static LocalDate day(String s, LocalDate fallback) {
        return s == null || s.isBlank() ? fallback : Req.date(Map.of("d", s), "d");
    }

    private static void ordered(LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw ApiException.badRequest("The start date must be before the end date.");
    }

    /** One row per resident with a balance: how much is owed, how old it is (current, 1-30, 31-60, 61-90, over 90 days) and since when. */
    @GetMapping("/receivables") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> receivables() {
        String base = " from billing.invoice i where i.status in ('OPEN','PARTIAL')";
        return ReportRows.of(db, """
                select coalesce(sum(i.amount - i.paid), 0) as outstanding,
                       coalesce(sum(i.amount - i.paid) filter (where i.due_date < current_date), 0) as overdue,
                       count(distinct i.tenant_id) filter (where i.due_date < current_date) as residents_overdue,
                       coalesce(sum(i.amount - i.paid) filter (where current_date - i.due_date > 60), 0) as over_60_days
                """ + base, new ArrayList<>(),
                """
                select i.tenant_id, max(i.tenant_name) as tenant_name, max(i.unit_label) as unit_label, (array_agg(i.property_id))[1] as property_id,
                       sum(i.amount - i.paid) as balance,
                       coalesce(sum(i.amount - i.paid) filter (where i.due_date >= current_date), 0) as not_due,
                       coalesce(sum(i.amount - i.paid) filter (where current_date - i.due_date between 1 and 30), 0) as days_1_30,
                       coalesce(sum(i.amount - i.paid) filter (where current_date - i.due_date between 31 and 60), 0) as days_31_60,
                       coalesce(sum(i.amount - i.paid) filter (where current_date - i.due_date between 61 and 90), 0) as days_61_90,
                       coalesce(sum(i.amount - i.paid) filter (where current_date - i.due_date > 90), 0) as days_over_90,
                       min(i.due_date) as oldest_due, count(*) as open_charges
                """ + base + " group by i.tenant_id order by balance desc", new ArrayList<>());
    }

    /** Charged, collected, spent and kept, by property and month, for a period (default the last 6 months). Drill to {@code transactions}. */
    @GetMapping("/income") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> income(@RequestParam(required = false) String from, @RequestParam(required = false) String to, @RequestParam(required = false) UUID propertyId) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(5).withDayOfMonth(1));
        ordered(start, end);
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        String pf = propertyId == null ? "" : " and property_id = ?";
        if (propertyId != null) a.add(propertyId);
        String months = "select property_id, to_char(d, 'YYYY-MM') as month from (select distinct property_id, date_trunc('month', created_at) as d from billing.invoice where created_at::date between ?::date and ?::date" + pf
                + " union select distinct property_id, date_trunc('month', created_at) from billing.payment where created_at::date between ?::date and ?::date" + pf
                + " union select distinct property_id, date_trunc('month', incurred_on::timestamp) from billing.expense where incurred_on between ?::date and ?::date" + pf + ") m";
        List<Object> args = new ArrayList<>();
        for (int i = 0; i < 3; i++) args.addAll(a);
        String sel = """
                select m.property_id, m.month,
                  coalesce((select sum(amount) from billing.invoice x where x.property_id = m.property_id and x.status <> 'VOID' and to_char(x.created_at, 'YYYY-MM') = m.month), 0) as charged,
                  coalesce((select sum(amount) from billing.payment x where x.property_id = m.property_id and x.status = 'SUCCEEDED' and to_char(x.created_at, 'YYYY-MM') = m.month), 0) as collected,
                  coalesce((select sum(amount) from billing.expense x where x.property_id = m.property_id and to_char(x.incurred_on, 'YYYY-MM') = m.month), 0) as expenses
                from (""" + months + ") m";
        String rows = "select r.*, (r.collected - r.expenses) as net from (" + sel + ") r order by r.month desc, r.property_id";
        Map<String, Object> out = ReportRows.of(db, null, new ArrayList<>(), rows, args);
        double charged = 0, collected = 0, spent = 0;
        for (Object o : (List<?>) out.get("rows")) {
            Map<?, ?> r = (Map<?, ?>) o;
            charged += ((Number) r.get("charged")).doubleValue(); collected += ((Number) r.get("collected")).doubleValue(); spent += ((Number) r.get("expenses")).doubleValue();
        }
        out.put("summary", Map.of("from", start.toString(), "to", end.toString(), "charged", charged, "collected", collected, "expenses", spent, "net", collected - spent,
                "collectionRatePct", charged == 0 ? 0 : Math.round(1000.0 * collected / charged) / 10.0));
        return out;
    }

    /** Every payment, charge and expense behind a figure: filter with {@code propertyId} and {@code month} (yyyy-MM), newest first. */
    @GetMapping("/transactions") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> transactions(@RequestParam(required = false) UUID propertyId, @RequestParam(required = false) String month, @RequestParam(required = false) UUID tenantId) {
        if (month != null && !month.matches("\\d{4}-\\d{2}")) throw ApiException.badRequest("Month must look like 2026-05.");
        List<Object> one = new ArrayList<>();
        String pf = propertyId == null ? "" : " and property_id = ?", mf = month == null ? "" : " and to_char(%s, 'YYYY-MM') = ?";
        if (propertyId != null) one.add(propertyId);
        if (month != null) one.add(month);
        // One resident's account: their payments and charges (an expense belongs to a property, never to a resident).
        String tf = tenantId == null ? "" : " and tenant_id = ?", noExpense = tenantId == null ? "" : " and false";
        List<Object> withTenant = new ArrayList<>(one);
        if (tenantId != null) withTenant.add(tenantId);
        String sql = "select * from (select 'PAYMENT' as type, p.created_at::date as date, p.tenant_name as who, ('Payment ' || p.reference) as description, p.amount, p.id as ref, p.property_id, p.status from billing.payment p where true" + pf + mf.formatted("p.created_at") + tf
                + " union all select 'CHARGE', i.created_at::date, i.tenant_name, i.description, i.amount, i.id, i.property_id, i.status from billing.invoice i where i.status <> 'VOID'" + pf.replace("property_id", "i.property_id") + mf.formatted("i.created_at") + tf.replace("tenant_id", "i.tenant_id")
                + " union all select 'EXPENSE', e.incurred_on, null, e.description, e.amount, e.id, e.property_id, e.category from billing.expense e where true" + pf.replace("property_id", "e.property_id") + mf.formatted("e.incurred_on") + noExpense + ") t order by date desc";
        List<Object> args = new ArrayList<>();
        args.addAll(withTenant);
        args.addAll(withTenant);
        args.addAll(one);
        String sum = "select coalesce(sum(amount) filter (where type = 'PAYMENT' and status = 'SUCCEEDED'), 0) as collected, coalesce(sum(amount) filter (where type = 'CHARGE'), 0) as charged, coalesce(sum(amount) filter (where type = 'EXPENSE'), 0) as expenses from (" + sql.replace(" order by date desc", "") + ") s";
        return ReportRows.of(db, sum, args, sql, args);
    }

    /** How payments are going, by month and method: how many, how much, how many failed. */
    @GetMapping("/payments") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> payments(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(5).withDayOfMonth(1));
        ordered(start, end);
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        String base = " from billing.payment p where p.created_at::date between ?::date and ?::date";
        return ReportRows.of(db, """
                select coalesce(sum(p.amount) filter (where p.status = 'SUCCEEDED'), 0) as collected, count(*) filter (where p.status = 'SUCCEEDED') as succeeded,
                       count(*) filter (where p.status = 'FAILED') as failed,
                       coalesce(round(100.0 * count(*) filter (where p.status = 'FAILED') / nullif(count(*), 0), 1), 0) as failure_pct,
                       coalesce(sum(p.fee_amount) filter (where p.status = 'SUCCEEDED'), 0) as fees
                """ + base, a,
                """
                select to_char(p.created_at, 'YYYY-MM') as month, p.method, p.source, count(*) filter (where p.status = 'SUCCEEDED') as payments,
                       coalesce(sum(p.amount) filter (where p.status = 'SUCCEEDED'), 0) as collected, count(*) filter (where p.status = 'FAILED') as failed,
                       coalesce(sum(p.fee_amount) filter (where p.status = 'SUCCEEDED'), 0) as fees
                """ + base + " group by 1, 2, 3 order by 1 desc, 2, 3", a);
    }

    /** Late fees, returned-payment fees and other charges by month: charged, collected and cancelled. */
    @GetMapping("/fees") @RequiresPermission("REPORT:VIEW") @Transactional(readOnly = true)
    public Map<String, Object> fees(@RequestParam(required = false) String from, @RequestParam(required = false) String to) {
        LocalDate end = day(to, LocalDate.now()), start = day(from, end.minusMonths(5).withDayOfMonth(1));
        ordered(start, end);
        List<Object> a = new ArrayList<>(List.of(start.toString(), end.toString()));
        String base = " from billing.invoice i where i.kind not in ('RENT','DEPOSIT') and i.created_at::date between ?::date and ?::date";
        return ReportRows.of(db, """
                select coalesce(sum(i.amount) filter (where i.status <> 'VOID'), 0) as charged, coalesce(sum(i.paid), 0) as collected,
                       coalesce(sum(i.amount) filter (where i.status = 'VOID'), 0) as cancelled, coalesce(sum(i.tax_amount) filter (where i.status <> 'VOID'), 0) as tax
                """ + base, a,
                """
                select to_char(i.created_at, 'YYYY-MM') as month, i.kind, count(*) as charges, coalesce(sum(i.amount) filter (where i.status <> 'VOID'), 0) as charged,
                       coalesce(sum(i.paid), 0) as collected, coalesce(sum(i.amount) filter (where i.status = 'VOID'), 0) as cancelled,
                       coalesce(sum(i.tax_amount) filter (where i.status <> 'VOID'), 0) as tax
                """ + base + " group by 1, 2 order by 1 desc, 2", a);
    }
}
