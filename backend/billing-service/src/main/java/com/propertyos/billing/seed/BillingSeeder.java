package com.propertyos.billing.seed;

import com.propertyos.billing.service.StatementService;
import com.propertyos.common.demo.DemoData;
import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Seeds three months of rent history per resident according to their payment profile, plus deposits, expenses and statements. */
@Component
public class BillingSeeder implements EventHandler {
    private static final Logger log = LoggerFactory.getLogger(BillingSeeder.class);
    private final Db db;
    private final StatementService statements;
    private final TransactionTemplate tx;
    private final boolean demo;

    public BillingSeeder(Db db, StatementService statements, PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demo) {
        this.db = db;
        this.statements = statements;
        this.tx = new TransactionTemplate(tm);
        this.demo = demo;
    }

    /**
     * On startup in demo mode, creates sample invoices, payments, statements and deposits when the database is empty.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void seedIfEmpty() {
        if (!demo) return;
        for (DemoData.Org org : List.of(DemoData.SUMMIT, DemoData.HARBOR)) {
            UserContextHolder.runAsSystem(org.id(), () -> {
                tx.executeWithoutResult(s -> {
                    if (db.value(Long.class, "select count(*) from billing.invoice") > 0) return;
                    seed(org.id());
                    log.info("Seeded billing demo data for {}", org.name());
                });
                return null;
            });
        }
    }

    /**
     * Name under which this handler's event position is stored.
     */
    @Override
    public String subscriber() {
        return "billing.demo-reset";
    }

    /**
     * Event types this handler wants.
     */
    @Override
    public Set<String> eventTypes() {
        return Set.of("DemoResetRequested");
    }

    /**
     * Rebuilds the demo company's billing data when a demo reset is requested.
     */
    @Override
    public void handle(DomainEvent event) {
        UUID org = event.orgId();
        UserContextHolder.runAsSystem(org, () -> {
            tx.executeWithoutResult(s -> {
                for (String t : List.of("owner_statement", "owner_tax_profile", "expense", "deposit", "ledger_entry", "payment_method", "payment", "invoice"))
                    db.update("delete from billing." + t + " where org_id = ?", org);
                seed(org);
            });
            return null;
        });
    }

    private void seed(UUID org) {
        LocalDate today = LocalDate.now();
        YearMonth cur = YearMonth.from(today);
        Map<UUID, String> tenantNames = new HashMap<>();
        DemoData.TENANTS.forEach(t -> tenantNames.put(t.id(), t.name()));
        Random rnd = new Random(7);
        int idx = 0;
        for (DemoData.Lease l : DemoData.leases()) {
            if (!l.orgId().equals(org)) continue;
            DemoData.Property p = DemoData.property(l.propertyId());
            DemoData.Unit u = DemoData.units().stream().filter(x -> x.id().equals(l.unitId())).findFirst().orElseThrow();
            String label = p.name() + " · Unit " + u.number();
            String name = tenantNames.get(l.tenantId());
            Map<String, Object> ctx = Map.of("org_id", org, "property_id", p.id(), "owner_id", p.ownerId(), "tenant_id", l.tenantId());
            // Deposit, paid at move-in.
            UUID depId = DemoData.id("deposit:" + l.id());
            db.update("insert into billing.deposit (id, org_id, property_id, owner_id, tenant_id, lease_id, tenant_name, unit_label, amount, held, status, state) values (?,?,?,?,?,?,?,?,?,?,'HELD',?)",
                    depId, org, p.id(), p.ownerId(), l.tenantId(), l.id(), name, "Unit " + u.number(), l.deposit(), l.deposit(), p.state());
            for (int back = 2; back >= 0; back--) {
                YearMonth ym = cur.minusMonths(back);
                LocalDate due = ym.atDay(1);
                invoicePaid(l, ctx, name, label, ym, due, today);
            }
            // Next month's invoice, issued but not yet due.
            YearMonth nxt = cur.plusMonths(1);
            insertInvoice(DemoData.id("inv:" + l.id() + ":" + nxt), l, ctx, name, label, "RENT", rentDesc(nxt), nxt.toString(), l.rent(), BigDecimal.ZERO, nxt.atDay(1), "OPEN", today.minusDays(2));
            if (idx % 4 == 0) {
                db.update("insert into billing.payment_method (id, org_id, tenant_id, type, last4, label, autopay) values (?,?,?,?,?,?,?)",
                        DemoData.id("pm:" + l.tenantId()), org, l.tenantId(), "ACH", "4" + (100 + idx), "Bank account ending 4" + (100 + idx), true);
            } else {
                db.update("insert into billing.payment_method (id, org_id, tenant_id, type, last4, label, autopay) values (?,?,?,?,?,?,?)",
                        DemoData.id("pm:" + l.tenantId()), org, l.tenantId(), "CARD", "42" + (10 + idx), "Card ending 42" + (10 + idx), false);
            }
            idx++;
        }
        // Maintenance expenses charged to owners last month.
        YearMonth prev = cur.minusMonths(1);
        for (DemoData.Property p : DemoData.properties()) {
            if (!p.orgId().equals(org)) continue;
            db.update("insert into billing.expense (id, org_id, property_id, owner_id, description, category, amount, incurred_on) values (?,?,?,?,?,'MAINTENANCE',?,?)",
                    DemoData.id("exp:" + p.id()), org, p.id(), p.ownerId(), "HVAC service and filter replacement", 180 + rnd.nextInt(220), prev.atDay(12));
        }
        List<DemoData.Owner> owners = DemoData.owners().stream().filter(o -> o.orgId().equals(org)).toList();
        for (int i = 0; i < owners.size(); i++) {
            DemoData.Owner o = owners.get(i);
            // Every owner but the last has a W-9 on file, so the compliance check has something to show.
            boolean w9 = i < owners.size() - 1;
            if (w9) db.update("insert into billing.owner_tax_profile (owner_id, org_id, legal_name, tin_type, tin_last4, w9_received_at, updated_by) values (?,?,?,?,?,current_date - 90,'Demo data') on conflict do nothing",
                    o.id(), org, o.name(), "SSN", String.format("%04d", Math.abs(o.name().hashCode()) % 10000));
            Map<String, Object> st = statements.generate(o.id(), prev.toString(), o.name(), o.managementFeePercent());
            if (st != null && w9) statements.publish((UUID) st.get("id"));
        }
        db.update("update billing.invoice i set state = d.state from billing.deposit d where d.lease_id = i.lease_id and i.state is null and d.state is not null");
    }

    private String rentDesc(YearMonth ym) {
        return "Rent for " + ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.US) + " " + ym.getYear();
    }

    private void invoicePaid(DemoData.Lease l, Map<String, Object> ctx, String name, String label, YearMonth ym, LocalDate due, LocalDate today) {
        boolean currentMonth = ym.equals(YearMonth.from(today));
        BigDecimal rent = l.rent();
        String profile = l.payProfile();
        UUID invId = DemoData.id("inv:" + l.id() + ":" + ym);
        boolean paid;
        LocalDate paidOn;
        BigDecimal fee = BigDecimal.ZERO;
        switch (profile) {
            case "LATE" -> {
                paid = !currentMonth || today.isAfter(due.plusDays(6));
                paidOn = due.plusDays(7);
                fee = rent.multiply(BigDecimal.valueOf(0.05)).setScale(2, java.math.RoundingMode.HALF_UP);
            }
            case "DELINQUENT" -> {
                boolean old = ym.isBefore(YearMonth.from(today).minusMonths(1));
                paid = old;
                paidOn = due.plusDays(2);
                fee = old ? BigDecimal.ZERO : rent.multiply(BigDecimal.valueOf(0.05)).setScale(2, java.math.RoundingMode.HALF_UP);
            }
            default -> {
                paid = !due.isAfter(today) || true;
                paidOn = due.isAfter(today) ? due : due.plusDays(0);
            }
        }
        if (paid && paidOn.isAfter(today)) paidOn = today;
        insertInvoice(invId, l, ctx, name, label, "RENT", rentDesc(ym), ym.toString(), rent, paid ? rent : BigDecimal.ZERO, due, paid ? "PAID" : "OPEN", due.minusDays(5));
        if (paid) recordPayment(invId, l, ctx, name, "RENT", rent, paidOn, "ACH");
        if (fee.signum() > 0 && !due.plusDays(5).isAfter(today)) {
            boolean feePaid = paid;
            UUID feeId = DemoData.id("fee:" + l.id() + ":" + ym);
            insertInvoice(feeId, l, ctx, name, label, "LATE_FEE", "Late fee for " + rentDesc(ym), ym.toString(), fee, feePaid ? fee : BigDecimal.ZERO, due.plusDays(6), feePaid ? "PAID" : "OPEN", due.plusDays(6));
            if (feePaid) recordPayment(feeId, l, ctx, name, "LATE_FEE", fee, paidOn, "ACH");
        }
    }

    private void insertInvoice(UUID id, DemoData.Lease l, Map<String, Object> ctx, String name, String label, String kind, String desc, String period,
                               BigDecimal amount, BigDecimal paid, LocalDate due, String status, LocalDate created) {
        db.update("""
                insert into billing.invoice (id, org_id, property_id, owner_id, tenant_id, lease_id, tenant_name, unit_label, kind, description, period, amount, paid, due_date, status, created_by, created_at)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,'System',?)""",
                id, ctx.get("org_id"), ctx.get("property_id"), ctx.get("owner_id"), ctx.get("tenant_id"), l.id(), name, label, kind, desc, period, amount, paid, due, status,
                created.atStartOfDay().atOffset(ZoneOffset.UTC));
        db.update("insert into billing.ledger_entry (id, org_id, property_id, owner_id, tenant_id, type, amount, memo, ref_type, ref_id, created_at) values (?,?,?,?,?,'CHARGE',?,?,'INVOICE',?,?)",
                UUID.randomUUID(), ctx.get("org_id"), ctx.get("property_id"), ctx.get("owner_id"), ctx.get("tenant_id"), amount, desc, id, created.atStartOfDay().atOffset(ZoneOffset.UTC));
    }

    private void recordPayment(UUID invId, DemoData.Lease l, Map<String, Object> ctx, String name, String kind, BigDecimal amount, LocalDate on, String method) {
        UUID pid = UUID.randomUUID();
        String alloc = db.json(List.of(Map.of("invoiceId", invId.toString(), "amount", amount, "kind", kind)));
        db.update("""
                insert into billing.payment (id, org_id, property_id, owner_id, tenant_id, tenant_name, amount, method, method_last4, status, reference, allocations, source, created_at)
                values (?,?,?,?,?,?,?,?,?,'SUCCEEDED',?,?::jsonb,'MANUAL',?)""",
                pid, ctx.get("org_id"), ctx.get("property_id"), ctx.get("owner_id"), ctx.get("tenant_id"), name, amount, method, "6789",
                "PAY-" + pid.toString().substring(0, 8).toUpperCase(), alloc, on.atTime(10, 0).atOffset(ZoneOffset.UTC));
        db.update("insert into billing.ledger_entry (id, org_id, property_id, owner_id, tenant_id, type, amount, memo, ref_type, ref_id, created_at) values (?,?,?,?,?,'PAYMENT',?,?,'PAYMENT',?,?)",
                UUID.randomUUID(), ctx.get("org_id"), ctx.get("property_id"), ctx.get("owner_id"), ctx.get("tenant_id"), amount.negate(), "Payment received", pid, on.atTime(10, 0).atOffset(ZoneOffset.UTC));
    }
}
