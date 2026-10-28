package com.propertyos.billing.service;

import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;

/** Owner statements, expenses and security-deposit settlement. */
@Service
public class StatementService {
    private final Db db;
    private final ConfigClient config;
    private final AuditService audit;
    private final EventPublisher events;
    private final Notifier notifier;

    public StatementService(Db db, ConfigClient config, AuditService audit, EventPublisher events, Notifier notifier) {
        this.db = db;
        this.config = config;
        this.audit = audit;
        this.events = events;
        this.notifier = notifier;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> statements() {
        return db.list("select * from billing.owner_statement order by period desc, owner_name");
    }

    @Transactional(readOnly = true)
    public Map<String, Object> statement(UUID id) {
        return db.one("Statement", "select * from billing.owner_statement where id = ?", id);
    }

    /** Builds (or rebuilds a draft of) an owner's statement: rent collected less management fee and expenses. */
    @Transactional
    public Map<String, Object> generate(UUID ownerId, String period, String ownerName, BigDecimal feePct) {
        YearMonth ym = YearMonth.parse(period);
        LocalDate from = ym.atDay(1), to = ym.plusMonths(1).atDay(1);
        Map<String, Object> existing = db.find("select id, status from billing.owner_statement where owner_id = ? and period = ?", ownerId, period).orElse(null);
        if (existing != null && !"DRAFT".equals(existing.get("status"))) throw ApiException.conflict("That statement has already been published.");
        BigDecimal rent = db.value(BigDecimal.class, """
                select coalesce(sum((a->>'amount')::numeric), 0) from billing.payment p, jsonb_array_elements(p.allocations) a
                where p.owner_id = ? and p.status = 'SUCCEEDED' and a->>'kind' = 'RENT' and p.created_at >= ? and p.created_at < ?""", ownerId, from, to);
        BigDecimal fees = db.value(BigDecimal.class, """
                select coalesce(sum((a->>'amount')::numeric), 0) from billing.payment p, jsonb_array_elements(p.allocations) a
                where p.owner_id = ? and p.status = 'SUCCEEDED' and a->>'kind' <> 'RENT' and a->>'kind' <> 'DEPOSIT' and p.created_at >= ? and p.created_at < ?""", ownerId, from, to);
        BigDecimal expenses = db.value(BigDecimal.class, "select coalesce(sum(amount), 0) from billing.expense where owner_id = ? and incurred_on >= ? and incurred_on < ?", ownerId, from, to);
        BigDecimal pct = feePct != null ? feePct : config.number("fees.managementFeePercent", "8");
        BigDecimal mgmt = rent.multiply(pct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        BigDecimal net = rent.add(fees).subtract(mgmt).subtract(expenses);
        List<Map<String, Object>> lines = new ArrayList<>(db.list("""
                select description, amount, incurred_on as date, 'Expense' as type from billing.expense where owner_id = ? and incurred_on >= ? and incurred_on < ? order by incurred_on""", ownerId, from, to));
        UUID id = existing == null ? UUID.randomUUID() : (UUID) existing.get("id");
        if (existing == null)
            db.update("""
                    insert into billing.owner_statement (id, org_id, owner_id, owner_name, period, rent_collected, fees_collected, mgmt_fee, expenses, net_payout, lines)
                    select ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb""",
                    id, com.propertyos.common.security.UserContextHolder.orgId(), ownerId, ownerName, period, rent, fees, mgmt, expenses, net, db.json(lines));
        else
            db.update("update billing.owner_statement set rent_collected=?, fees_collected=?, mgmt_fee=?, expenses=?, net_payout=?, lines=?::jsonb where id=?",
                    rent, fees, mgmt, expenses, net, db.json(lines), id);
        return statement(id);
    }

    @Transactional
    public Map<String, Object> publish(UUID id) {
        Map<String, Object> s = statement(id);
        if (!"DRAFT".equals(s.get("status"))) throw ApiException.conflict("This statement is already published.");
        db.update("update billing.owner_statement set status = 'PUBLISHED', published_at = now() where id = ?", id);
        audit.record("OwnerStatement", id, "PUBLISHED", "DRAFT", "PUBLISHED", Map.of("net", s.get("net_payout")));
        notifier.toParty((UUID) s.get("owner_id"), "Your " + s.get("period") + " statement is ready", "Net payout: $" + s.get("net_payout"), "/payments/statements");
        events.publish("StatementPublished", "OwnerStatement", id, Map.of("ownerId", s.get("owner_id").toString(), "net", s.get("net_payout").toString()));
        return statement(id);
    }

    @Transactional
    public Map<String, Object> addExpense(Map<String, Object> b) {
        UUID id = UUID.randomUUID();
        UUID prop = Req.requiredUuid(b, "propertyId");
        UUID owner = Req.requiredUuid(b, "ownerId");
        db.update("insert into billing.expense (id, org_id, property_id, owner_id, description, category, amount, incurred_on) values (?,?,?,?,?,?,?,?)",
                id, com.propertyos.common.security.UserContextHolder.orgId(), prop, owner, Req.required(b, "description"),
                Optional.ofNullable(Req.str(b, "category")).orElse("OTHER"), Req.requiredMoney(b, "amount"), Optional.ofNullable(Req.date(b, "date")).orElse(LocalDate.now()));
        return Map.of("id", id);
    }

    // ---------- Deposits ----------
    @Transactional(readOnly = true)
    public List<Map<String, Object>> deposits() {
        return db.list("select * from billing.deposit order by created_at desc");
    }

    /** Settles a deposit: itemised deductions, refund of the remainder, and the legal return deadline recorded. */
    @Transactional
    public Map<String, Object> settleDeposit(UUID id, List<Map<String, Object>> deductions) {
        Map<String, Object> d = db.one("Deposit", "select * from billing.deposit where id = ?", id);
        if ("SETTLED".equals(d.get("status"))) throw ApiException.conflict("This deposit has already been settled.");
        if (!"REFUND_DUE".equals(d.get("status")))
            throw ApiException.conflict("The deposit can be settled after the tenant moves out.");
        BigDecimal held = (BigDecimal) d.get("held");
        BigDecimal ded = BigDecimal.ZERO;
        for (Map<String, Object> x : deductions) {
            if (x.get("description") == null || String.valueOf(x.get("description")).isBlank()) throw ApiException.badRequest("Each deduction needs a description.");
            BigDecimal amt = new BigDecimal(String.valueOf(x.get("amount")));
            if (amt.signum() <= 0) throw ApiException.badRequest("Deductions must be greater than zero.");
            ded = ded.add(amt);
        }
        if (ded.compareTo(held) > 0) throw ApiException.badRequest("Deductions of $" + ded + " exceed the $" + held + " held. Bill the remainder as a separate charge.");
        BigDecimal refund = held.subtract(ded);
        db.update("update billing.deposit set status = 'SETTLED', deductions = ?::jsonb, refund = ?, settled_at = now(), updated_at = now() where id = ?", db.json(deductions), refund, id);
        audit.record("Deposit", id, "SETTLED", "REFUND_DUE", "SETTLED", Map.of("refund", refund, "deductions", ded));
        events.publish("DepositSettled", "Deposit", id, Map.of("tenantId", d.get("tenant_id").toString(), "refund", refund.toString()));
        notifier.toParty((UUID) d.get("tenant_id"), "Security deposit settled", "Your refund is $" + refund + (ded.signum() > 0 ? " after $" + ded + " in deductions." : "."), "/payments");
        return db.one("Deposit", "select * from billing.deposit where id = ?", id);
    }
}
