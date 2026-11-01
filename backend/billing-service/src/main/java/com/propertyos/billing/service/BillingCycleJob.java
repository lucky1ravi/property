package com.propertyos.billing.service;

import com.propertyos.common.rls.Db;
import com.propertyos.common.security.SuspendedOrgs;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

/**
 * The daily billing run: for every company, creates the next rent invoices, charges late fees and collects autopay.
 * Without it, rent would only be invoiced when somebody pressed the button.
 */
@Service
public class BillingCycleJob {
    private static final Logger log = LoggerFactory.getLogger(BillingCycleJob.class);

    private final Db db;
    private final BillingService billing;
    private final PlatformTransactionManager tm;
    private SuspendedOrgs suspended;

    public BillingCycleJob(Db db, BillingService billing, PlatformTransactionManager tm) {
        this.db = db;
        this.billing = billing;
        this.tm = tm;
    }

    @Autowired(required = false)
    void setSuspended(SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    /** Runs the cycle for each company that has billing activity. One company failing does not stop the others. Returns how many ran. */
    @Scheduled(cron = "${propertyos.billing.cycle-cron:0 0 2 * * *}", zone = "America/Chicago")
    public int daily() {
        List<UUID> orgs = UserContextHolder.runAsSystem(null, () -> new TransactionTemplate(tm).execute(st ->
                db.list("select distinct org_id from billing.invoice").stream().map(r -> (UUID) r.get("org_id")).toList()));
        int ran = 0;
        for (UUID org : orgs == null ? List.<UUID>of() : orgs) {
            if (suspended != null && suspended.contains(org)) continue; // a suspended company is not billed while suspended
            try {
                // With more than one billing instance running, only one may run a company's cycle at a time, or autopay could be taken twice.
                Boolean ran1 = UserContextHolder.runAsSystem(org, () -> new TransactionTemplate(tm).execute(st -> {
                    if (!Boolean.TRUE.equals(db.value(Boolean.class, "select pg_try_advisory_xact_lock(hashtext(?))", "billing-cycle:" + org))) return false;
                    billing.runCycle();
                    return true;
                }));
                if (Boolean.TRUE.equals(ran1)) ran++;
            } catch (RuntimeException e) {
                log.warn("Billing cycle failed for company {}: {}", org, e.getMessage());
            }
        }
        return ran;
    }
}
