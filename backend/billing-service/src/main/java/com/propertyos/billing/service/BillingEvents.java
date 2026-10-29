package com.propertyos.billing.service;

import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Reacts to leasing and maintenance events: move-in charges, deposit tracking, vendor cost to owner expense. */
@Component
public class BillingEvents implements EventHandler {
    private final Db db;
    private final BillingService billing;
    private final ConfigClient config;

    public BillingEvents(Db db, BillingService billing, ConfigClient config) {
        this.db = db;
        this.billing = billing;
        this.config = config;
    }

    @Override
    public String subscriber() {
        return "billing.events";
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("LeaseActivated", "LeaseEnded", "WorkOrderCompleted");
    }

    @Override
    @Transactional
    public void handle(DomainEvent e) {
        switch (e.type()) {
            case "LeaseActivated" -> onActivated(e);
            case "LeaseEnded" -> onEnded(e);
            case "WorkOrderCompleted" -> onWorkOrder(e);
            default -> { }
        }
    }

    private void onActivated(DomainEvent e) {
        UUID leaseId = e.aggregateId();
        if (db.value(Long.class, "select count(*) from billing.deposit where lease_id = ?", leaseId) > 0) return;
        Map<String, Object> lease = new HashMap<>();
        lease.put("id", leaseId);
        lease.put("org_id", e.orgId());
        lease.put("property_id", e.uuid("propertyId"));
        lease.put("owner_id", e.uuid("ownerId"));
        lease.put("tenant_id", e.uuid("tenantId"));
        lease.put("tenant_name", e.text("tenantName"));
        lease.put("unit_label", e.text("unitLabel"));
        lease.put("property_name", e.text("propertyName"));
        if (e.text("state") != null) lease.put("state", e.text("state"));
        BigDecimal deposit = new BigDecimal(e.text("deposit"));
        BigDecimal rent = new BigDecimal(e.text("rent"));
        LocalDate start = LocalDate.parse(e.text("startDate"));
        db.update("""
                insert into billing.deposit (id, org_id, property_id, owner_id, tenant_id, lease_id, tenant_name, unit_label, amount, held, status, state)
                values (?,?,?,?,?,?,?,?,?,0,'PENDING',?)""", UUID.randomUUID(), e.orgId(), lease.get("property_id"), lease.get("owner_id"), lease.get("tenant_id"),
                leaseId, lease.get("tenant_name"), lease.get("unit_label"), deposit, e.text("state"));
        LocalDate due = start.isBefore(LocalDate.now()) ? LocalDate.now().plusDays(3) : start.minusDays(1);
        if (deposit.signum() > 0) billing.insertInvoice(lease, "DEPOSIT", "Security deposit", null, deposit, due);
        // First month's rent, prorated by days remaining when the lease starts mid-month.
        YearMonth ym = YearMonth.from(start);
        int days = ym.lengthOfMonth() - start.getDayOfMonth() + 1;
        BigDecimal first = start.getDayOfMonth() == 1 ? rent
                : rent.multiply(BigDecimal.valueOf(days)).divide(BigDecimal.valueOf(ym.lengthOfMonth()), 2, java.math.RoundingMode.HALF_UP);
        billing.insertInvoice(lease, "RENT", "Rent for " + ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " " + ym.getYear()
                + (start.getDayOfMonth() == 1 ? "" : " (prorated, " + days + " days)"), ym.toString(), first, due);
    }

    private void onEnded(DomainEvent e) {
        int refundDays = config.stateRule(e.text("state") == null ? "TX" : e.text("state")).path("depositRefundDays").asInt(30);
        db.update("update billing.deposit set status = 'REFUND_DUE', refund_deadline = ?, updated_at = now() where lease_id = ? and status in ('HELD','PENDING')",
                LocalDate.now().plusDays(refundDays), e.aggregateId());
    }

    private void onWorkOrder(DomainEvent e) {
        String cost = e.text("cost");
        if (cost == null || new BigDecimal(cost).signum() <= 0 || e.uuid("ownerId") == null) return;
        if (db.value(Long.class, "select count(*) from billing.expense where ref_id = ?", e.aggregateId()) > 0) return;
        db.update("insert into billing.expense (id, org_id, property_id, owner_id, description, category, amount, ref_id) values (?,?,?,?,?,'MAINTENANCE',?,?)",
                UUID.randomUUID(), e.orgId(), e.uuid("propertyId"), e.uuid("ownerId"), Optional.ofNullable(e.text("title")).orElse("Maintenance work order"),
                new BigDecimal(cost), e.aggregateId());
    }
}
