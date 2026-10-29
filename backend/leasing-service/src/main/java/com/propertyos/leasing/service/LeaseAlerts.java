package com.propertyos.leasing.service;

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

import java.time.LocalDate;
import java.util.*;

/** Lease-expiry deadlines: shown on the deadlines page and sent as alerts at the configured days before the lease ends. */
@Service
public class LeaseAlerts {
    private final Db db;
    private final ConfigClient config;
    private final Notifier notifier;
    private final PlatformTransactionManager tm;

    public LeaseAlerts(Db db, ConfigClient config, Notifier notifier, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.notifier = notifier;
        this.tm = tm;
    }

    private List<Integer> thresholds() {
        return Deadlines.thresholds(config, "alerts/leaseExpiry/daysBefore", List.of(30, 60, 90));
    }

    /**
     * Returns lease expiries inside the alert horizon, or nothing when lease-expiry alerts are switched off.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadlines() {
        if (!config.flag("alerts/leaseExpiry/enabled", true)) return List.of();
        int horizon = thresholds().get(thresholds().size() - 1) + 30;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> l : db.list("""
                select id, property_id, owner_id, tenant_id, tenant_name, unit_label, property_name, end_date, status
                from leasing.lease where status in ('ACTIVE','RENEWAL_OFFERED','NOTICE_GIVEN') and end_date <= current_date + ?::int order by end_date""", horizon)) {
            LocalDate end = LocalDate.parse(String.valueOf(l.get("end_date")));
            boolean notice = "NOTICE_GIVEN".equals(l.get("status"));
            out.add(Deadlines.item("LEASE_EXPIRY", "Lease ends: " + l.get("tenant_name") + ", " + l.get("unit_label"),
                    l.get("property_name") + (notice ? " · notice already given" : "" ) + " · lease ends " + end, end, "/leasing/leases", String.valueOf(l.get("id")),
                    List.of(new String[]{"PARTY", String.valueOf(l.get("tenant_id"))}, new String[]{"PARTY", String.valueOf(l.get("owner_id"))},
                            new String[]{"PROPERTY", String.valueOf(l.get("property_id"))})));
        }
        return out;
    }

    /** Sends any alert that has reached a new reminder point. Safe to run repeatedly: each is sent once. */
    @Transactional
    public int run() {
        List<Integer> th = thresholds();
        int sent = 0;
        for (Map<String, Object> d : deadlines()) sent += Deadlines.notifyAll(notifier, d, Deadlines.bucket((Long) d.get("days_left"), th), th);
        return sent;
    }

    /**
     * Daily timer: for each company, sends the lease alerts that are due.
     */
    @Scheduled(cron = "0 15 7 * * *", zone = "America/Chicago")
    public void daily() {
        List<UUID> orgs = UserContextHolder.runAsSystem(null, () -> new TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from leasing.lease").stream().map(r -> (UUID) r.get("org_id")).toList()));
        for (UUID org : orgs == null ? List.<UUID>of() : orgs)
            UserContextHolder.runAsSystem(org, () -> new TransactionTemplate(tm).execute(st -> run()));
    }
}
