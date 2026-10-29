package com.propertyos.billing.service;

import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Deadlines;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Deposit refund deadlines, sales-tax filing dates, 1099 dates and missing W-9s. */
@Service
public class BillingAlerts {
    private final Db db;
    private final ConfigClient config;
    private final Notifier notifier;
    private final PlatformTransactionManager tm;

    public BillingAlerts(Db db, ConfigClient config, Notifier notifier, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.notifier = notifier;
        this.tm = tm;
    }

    private static final List<String[]> FINANCE = List.of(new String[]{"ROLE", "ACCOUNTANT"}, new String[]{"ROLE", "PROPERTY_MANAGER"});

    private List<Integer> thresholds(String key, List<Integer> fallback) {
        return Deadlines.thresholds(config, "alerts/" + key + "/daysBefore", fallback);
    }

    private Map<String, List<Integer>> allThresholds() {
        return Map.of("DEPOSIT_REFUND", thresholds("depositRefund", List.of(1, 3, 7)), "TAX_FILING", thresholds("taxFiling", List.of(7, 14, 30)),
                "FORM_1099", thresholds("form1099", List.of(14, 30, 60)), "W9_MISSING", List.of(0));
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadlines() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (config.flag("alerts/depositRefund/enabled", true))
            for (Map<String, Object> d : db.list("select id, property_id, tenant_name, unit_label, held, refund_deadline, state from billing.deposit where status = 'REFUND_DUE' and refund_deadline is not null order by refund_deadline")) {
                LocalDate due = LocalDate.parse(String.valueOf(d.get("refund_deadline")));
                out.add(Deadlines.item("DEPOSIT_REFUND", "Deposit refund due: " + d.get("tenant_name") + ", " + d.get("unit_label"),
                        "$" + d.get("held") + " held. " + (d.get("state") == null ? "State" : d.get("state")) + " law sets the return deadline; late returns can cost the landlord penalties.", due, "/payments/deposits", String.valueOf(d.get("id")),
                        List.of(new String[]{"ROLE", "ACCOUNTANT"}, new String[]{"ROLE", "PROPERTY_MANAGER"}, new String[]{"PROPERTY", String.valueOf(d.get("property_id"))})));
            }
        if (config.flag("alerts/taxFiling/enabled", true) && config.flag("payments/tax/enabled", true)) {
            YearMonth prev = YearMonth.now().minusMonths(1);
            BigDecimal tax = db.value(BigDecimal.class, "select coalesce(sum(tax_amount), 0) from billing.invoice where status <> 'VOID' and to_char(created_at, 'YYYY-MM') = ?", prev.toString());
            if (tax.signum() > 0) {
                int day = Math.min(config.number("payments/tax/filingDay", "20").intValue(), YearMonth.now().lengthOfMonth());
                LocalDate due = YearMonth.now().atDay(day);
                out.add(Deadlines.item("TAX_FILING", "Sales tax return due for " + prev, "$" + tax + " of tax was billed in " + prev + ". File and remit by the date your state sets.", due, "/payments/tax", "tax-" + prev, FINANCE));
            }
        }
        if (config.flag("alerts/form1099/enabled", true)) {
            LocalDate today = LocalDate.now();
            int taxYear = today.isAfter(LocalDate.of(today.getYear(), 3, 31)) ? today.getYear() : today.getYear() - 1;
            LocalDate toOwners = LocalDate.of(taxYear + 1, 1, 31), toIrs = LocalDate.of(taxYear + 1, 3, 31);
            if (!today.isAfter(toIrs.plusDays(30))) {
                out.add(Deadlines.item("FORM_1099", "Send 1099-MISC copies to owners (tax year " + taxYear + ")", "Owner copies are due January 31. Check W-9s and the worksheet first.", toOwners, "/payments/tax", "1099-owners-" + taxYear, FINANCE));
                out.add(Deadlines.item("FORM_1099", "File 1099-MISC with the IRS (tax year " + taxYear + ")", "Electronic filing is due March 31.", toIrs, "/payments/tax", "1099-irs-" + taxYear, FINANCE));
            }
        }
        if (config.flag("alerts/w9Missing/enabled", true)) {
            Long missing = db.value(Long.class, """
                    select count(distinct s.owner_id) from billing.owner_statement s
                    where s.status in ('PUBLISHED','PAID') and not exists (select 1 from billing.owner_tax_profile p where p.owner_id = s.owner_id and p.w9_received_at is not null)""");
            Long drafts = db.value(Long.class, """
                    select count(distinct s.owner_id) from billing.owner_statement s
                    where s.status = 'DRAFT' and not exists (select 1 from billing.owner_tax_profile p where p.owner_id = s.owner_id and p.w9_received_at is not null)""");
            if (missing + drafts > 0)
                out.add(Deadlines.item("W9_MISSING", (missing + drafts) + " owner(s) have no W-9 on file",
                        drafts > 0 ? "A draft statement can't be published until the W-9 is recorded." : "Year-end 1099 reporting and backup withholding depend on it.", null, "/payments/tax",
                        "w9-" + java.time.temporal.IsoFields.WEEK_OF_WEEK_BASED_YEAR.getFrom(LocalDate.now()) + "-" + LocalDate.now().getYear(), FINANCE));
        }
        return out;
    }

    @Transactional
    public int run() {
        Map<String, List<Integer>> th = allThresholds();
        int sent = 0;
        for (Map<String, Object> d : deadlines()) {
            String type = String.valueOf(d.get("type"));
            Long days = (Long) d.get("days_left");
            String bucket = days == null ? "0" : Deadlines.bucket(days, th.get(type));
            sent += Deadlines.notifyAll(notifier, d, bucket, th.get(type));
        }
        return sent;
    }

    @Scheduled(cron = "0 5 7 * * *", zone = "America/Chicago")
    public void daily() {
        List<UUID> orgs = UserContextHolder.runAsSystem(null, () -> new TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from billing.invoice").stream().map(r -> (UUID) r.get("org_id")).toList()));
        for (UUID org : orgs == null ? List.<UUID>of() : orgs)
            UserContextHolder.runAsSystem(org, () -> new TransactionTemplate(tm).execute(st -> run()));
    }
}
