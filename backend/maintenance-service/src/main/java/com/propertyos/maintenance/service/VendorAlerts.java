package com.propertyos.maintenance.service;

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

/** Vendor insurance (certificate of insurance) expiry: vendors without current cover can no longer be dispatched, so staff and the vendor are warned early. */
@Service
public class VendorAlerts {
    private final Db db;
    private final ConfigClient config;
    private final Notifier notifier;
    private final PlatformTransactionManager tm;

    public VendorAlerts(Db db, ConfigClient config, Notifier notifier, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.notifier = notifier;
        this.tm = tm;
    }

    private List<Integer> thresholds() {
        return Deadlines.thresholds(config, "alerts/vendorInsurance/daysBefore", List.of(7, 14, 30));
    }

    /**
     * Returns vendor insurance expiries inside the alert horizon, or nothing when these alerts are switched off.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deadlines() {
        if (!config.flag("alerts/vendorInsurance/enabled", true)) return List.of();
        int horizon = thresholds().get(thresholds().size() - 1) + 30;
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> v : db.list("select id, name, insurance_expires from maintenance.vendor where active and insurance_expires is not null and insurance_expires <= current_date + ?::int order by insurance_expires", horizon)) {
            LocalDate d = LocalDate.parse(String.valueOf(v.get("insurance_expires")));
            out.add(Deadlines.item("VENDOR_INSURANCE", "Insurance expires: " + v.get("name"), "Certificate of insurance ends " + d + ". Request a renewed certificate to keep dispatching this vendor.", d,
                    "/maintenance/vendors", String.valueOf(v.get("id")),
                    List.of(new String[]{"PARTY", String.valueOf(v.get("id"))}, new String[]{"ROLE", "PROPERTY_MANAGER"}, new String[]{"ROLE", "BROKER"})));
        }
        return out;
    }

    /**
     * Sends the alerts that are due.
     *
     * @return how many notifications were sent
     */
    @Transactional
    public int run() {
        List<Integer> th = thresholds();
        int sent = 0;
        for (Map<String, Object> d : deadlines()) sent += Deadlines.notifyAll(notifier, d, Deadlines.bucket((Long) d.get("days_left"), th), th);
        return sent;
    }

    /**
     * Daily timer: for each company, sends the vendor alerts that are due.
     */
    @Scheduled(cron = "0 25 7 * * *", zone = "America/Chicago")
    public void daily() {
        List<UUID> orgs = UserContextHolder.runAsSystem(null, () -> new TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from maintenance.vendor").stream().map(r -> (UUID) r.get("org_id")).toList()));
        for (UUID org : orgs == null ? List.<UUID>of() : orgs)
            UserContextHolder.runAsSystem(org, () -> new TransactionTemplate(tm).execute(st -> run()));
    }
}
