package com.propertyos.billing.service;

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

    public ReportingService(Db db, Notifier notifier, EventPublisher events) {
        this.db = db;
        this.notifier = notifier;
        this.events = events;
    }

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
}
