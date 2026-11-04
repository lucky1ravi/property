package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.function.Supplier;

/**
 * The PropertyOS operator's side of listing boosts: every company's boosts in one list, cancelling a boost, giving a free one, the prices agreed with
 * individual companies, and promo codes. These endpoints are for platform administrators only (the controller checks the permission); the work runs
 * as the system because a boost belongs to a company and the operator is not one. Every change is audited with the operator's name, and the company
 * is told when its boost is ended or granted.
 */
@Service
public class BoostAdminService {
    private static final Logger log = LoggerFactory.getLogger(BoostAdminService.class);

    private final Db db;
    private final ConfigClient config;
    private final ServiceClient client;
    private final Notifier notifier;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public BoostAdminService(Db db, ConfigClient config, ServiceClient client, Notifier notifier, AuditService audit, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.client = client;
        this.notifier = notifier;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    /** Runs as the system for one company (or all, when null), in a transaction. */
    private <T> T as(UUID org, Supplier<T> work) {
        return UserContextHolder.runAsSystem(org, () -> tx.execute(st -> work.get()));
    }

    private String companyName(Object org) {
        if (org == null) return "Any company";
        String n = config.forOrg((UUID) org).path("branding").path("companyName").asText("").trim();
        return n.isEmpty() ? "Company" : n;
    }

    private void named(List<Map<String, Object>> rows) {
        Map<Object, String> names = new HashMap<>();
        for (Map<String, Object> r : rows) r.put("company_name", names.computeIfAbsent(r.get("org_id"), this::companyName));
    }

    // ----------------------------------------------------------------------------------------------- boosts

    /** Counts by state for the top of the page. */
    public Map<String, Object> overview() {
        return as(null, () -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("active", db.value(Long.class, "select count(*) from leasing.listing_boost where status = 'ACTIVE' and ends_at > now()"));
            m.put("pending", db.value(Long.class, "select count(*) from leasing.listing_boost where status = 'PENDING'"));
            m.put("granted_active", db.value(Long.class, "select count(*) from leasing.listing_boost where status = 'ACTIVE' and ends_at > now() and source = 'GRANT'"));
            m.put("ending_soon", db.value(Long.class, "select count(*) from leasing.listing_boost where status = 'ACTIVE' and ends_at > now() and ends_at <= now() + interval '3 days'"));
            return m;
        });
    }

    /** Every company's boosts, newest first. Paged and sortable like other lists ({@code page}, {@code size}, {@code sort}, {@code dir}, {@code q}). */
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = as(null, () -> db.page("""
                select b.id, b.org_id, b.listing_id, l.title as listing_title, b.days, b.amount, b.list_price, b.status, b.source, b.promo_code, b.discount_note,
                       b.starts_at, b.ends_at, b.invoice_id, b.granted_by, b.grant_reason, b.created_at
                from leasing.listing_boost b join leasing.listing l on l.id = b.listing_id order by b.created_at desc"""));
        named(rows);
        return rows;
    }

    /** Live listings the operator could grant a boost to, by title or company search text. At most 15. */
    public List<Map<String, Object>> eligible(String q) {
        String like = "%" + (q == null ? "" : q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")) + "%";
        List<Map<String, Object>> rows = as(null, () -> db.list("""
                select l.id, l.org_id, l.title from leasing.listing l
                where l.status = 'LIVE' and l.title ilike ?
                  and not exists (select 1 from leasing.listing_boost b where b.listing_id = l.id and b.status in ('PENDING', 'ACTIVE'))
                order by l.published_at desc nulls last limit 15""", like));
        named(rows);
        return rows;
    }

    /**
     * Ends a boost now. An unpaid one is cancelled and its invoice voided. A paid one simply stops: there is no automatic refund, so any money
     * back is a decision for the operator on the billing side. The company is told, with the reason.
     *
     * @throws ApiException (404) for an unknown boost, (409) when it is already over, (400) without a reason
     */
    public Map<String, Object> cancel(UUID id, String reason, String by) {
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give a reason. The company is told, and it is kept in the audit trail.");
        if (reason.length() > 300) throw ApiException.badRequest("Keep the reason under 300 characters.");
        Map<String, Object> b = as(null, () -> db.find("select id, org_id, listing_id, status, invoice_id, amount from leasing.listing_boost where id = ?", id)).orElseThrow(() -> ApiException.notFound("Boost"));
        UUID org = (UUID) b.get("org_id");
        String was = (String) b.get("status");
        if ("EXPIRED".equals(was)) throw ApiException.conflict("This boost is already over.");
        as(org, () -> {
            db.update("update leasing.listing_boost set status = 'EXPIRED', ends_at = least(ends_at, now()), updated_at = now() where id = ?", id);
            audit.record("Listing", (UUID) b.get("listing_id"), "BOOST_CANCELLED_BY_OPERATOR", was, "EXPIRED", Map.of("by", by, "reason", reason));
            return null;
        });
        if ("PENDING".equals(was) && b.get("invoice_id") != null) voidInvoice(org, (UUID) b.get("invoice_id"), "Cancelled by PropertyOS: " + reason);
        tell(org, "Your listing boost was ended by PropertyOS", reason, "/leasing/listings/" + b.get("listing_id"), "boost-cancel-" + id);
        return Map.of("cancelled", true, "was", was, "paid", !"PENDING".equals(was) && ((BigDecimal) b.get("amount")).signum() > 0);
    }

    /**
     * Gives a live listing a free boost starting now, with no invoice (support, goodwill, a promotion). The company is told.
     *
     * @throws ApiException (400) for a bad period or no reason, (404) when the listing is not live, (409) when it already has a boost
     */
    public Map<String, Object> grant(UUID listingId, int days, String reason, String by) {
        if (days < 1 || days > 90) throw ApiException.badRequest("A granted boost lasts between 1 and 90 days.");
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("Give a reason. It is kept in the audit trail.");
        if (reason.length() > 300) throw ApiException.badRequest("Keep the reason under 300 characters.");
        Map<String, Object> l = as(null, () -> db.find("select id, org_id, property_id, owner_id, title from leasing.listing where id = ? and status = 'LIVE'", listingId)).orElseThrow(() -> ApiException.notFound("Live listing"));
        UUID org = (UUID) l.get("org_id");
        UUID id = UUID.randomUUID();
        try {
            as(org, () -> {
                db.update("""
                        insert into leasing.listing_boost (id, org_id, listing_id, property_id, owner_id, days, starts_at, ends_at, amount, status, invoice_id, created_by, source, granted_by, grant_reason)
                        values (?,?,?,?,?,?, now(), now() + make_interval(days => ?), 0, 'ACTIVE', null, ?, 'GRANT', ?, ?)""",
                        id, org, listingId, l.get("property_id"), l.get("owner_id"), days, days, by, by, reason);
                audit.record("Listing", listingId, "BOOST_GRANTED", null, "ACTIVE", Map.of("days", days, "by", by, "reason", reason));
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("This listing already has a boost that is running or waiting for payment.");
        }
        tell(org, "PropertyOS gave your listing a free boost", "\"" + l.get("title") + "\" is boosted for " + days + " days at no charge. It shows as Sponsored in search.",
                "/leasing/listings/" + listingId, "boost-grant-" + id);
        return Map.of("id", id, "days", days, "status", "ACTIVE");
    }

    // ----------------------------------------------------------------------------------------------- agreed prices

    /** Prices agreed with individual companies. */
    public List<Map<String, Object>> prices() {
        List<Map<String, Object>> rows = as(null, () -> db.list("select org_id, days, price, note, created_by, updated_at from leasing.boost_company_price order by updated_at desc"));
        named(rows);
        return rows;
    }

    /** Sets (or changes) the price one company pays for one period. It replaces the standard price and the plan discount for that company. */
    public Map<String, Object> setPrice(UUID org, int days, Object price, String note, String by) {
        BigDecimal p;
        try {
            p = new BigDecimal(String.valueOf(price)).setScale(2, RoundingMode.HALF_UP);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Enter the price as a number, for example 39.00.");
        }
        if (p.signum() <= 0) throw ApiException.badRequest("The price must be more than zero. To give a free boost, use Grant a boost.");
        if (days < 1 || days > 365) throw ApiException.badRequest("Choose a period between 1 and 365 days.");
        if (note != null && note.length() > 200) throw ApiException.badRequest("Keep the note under 200 characters.");
        as(org, () -> {
            db.update("""
                    insert into leasing.boost_company_price (org_id, days, price, note, created_by) values (?,?,?,?,?)
                    on conflict (org_id, days) do update set price = excluded.price, note = excluded.note, created_by = excluded.created_by, updated_at = now()""", org, days, p, note, by);
            audit.record("Organization", org, "BOOST_PRICE_SET", null, null, Map.of("days", days, "price", p.toString(), "by", by));
            return null;
        });
        return Map.of("saved", true);
    }

    /** Takes a company back to the standard price for a period. */
    public Map<String, Object> removePrice(UUID org, int days, String by) {
        as(org, () -> {
            if (db.update("delete from leasing.boost_company_price where org_id = ? and days = ?", org, days) == 0) throw ApiException.notFound("Agreed price");
            audit.record("Organization", org, "BOOST_PRICE_REMOVED", null, null, Map.of("days", days, "by", by));
            return null;
        });
        return Map.of("removed", true);
    }

    // ----------------------------------------------------------------------------------------------- promo codes

    /** All promo codes with how often each has been used. */
    public List<Map<String, Object>> promos() {
        List<Map<String, Object>> rows = as(null, () -> db.list("""
                select code, org_id, percent_off, amount_off, valid_from, valid_until, max_uses, uses, active, note, created_by, created_at
                from leasing.boost_promo order by created_at desc"""));
        for (Map<String, Object> r : rows) r.put("company_name", r.get("org_id") == null ? "Any company" : companyName(r.get("org_id")));
        return rows;
    }

    /**
     * Makes a promo code. Body: code (letters, digits, - and _), percentOff or amountOff (exactly one), optional orgId (only that company can use it),
     * validFrom, validUntil, maxUses, note.
     */
    public Map<String, Object> createPromo(Map<String, Object> b, String by) {
        String code = Optional.ofNullable(Req.str(b, "code")).orElse("").trim().toUpperCase();
        if (!code.matches("[A-Z0-9_-]{3,40}")) throw ApiException.badRequest("A code is 3 to 40 letters, digits, - or _.");
        Integer pct = Req.integer(b, "percentOff");
        Object amountRaw = b.get("amountOff");
        BigDecimal amount = null;
        if (amountRaw != null && !String.valueOf(amountRaw).isBlank()) {
            try { amount = new BigDecimal(String.valueOf(amountRaw)).setScale(2, RoundingMode.HALF_UP); } catch (RuntimeException e) { throw ApiException.badRequest("Enter the amount off as a number."); }
        }
        if ((pct == null) == (amount == null)) throw ApiException.badRequest("Give either a percentage off or an amount off, not both.");
        if (pct != null && (pct < 1 || pct > 100)) throw ApiException.badRequest("A percentage off is between 1 and 100.");
        if (amount != null && amount.signum() <= 0) throw ApiException.badRequest("The amount off must be more than zero.");
        LocalDate from = date(b, "validFrom"), until = date(b, "validUntil");
        if (from != null && until != null && until.isBefore(from)) throw ApiException.badRequest("The code ends before it starts.");
        Integer max = Req.integer(b, "maxUses");
        if (max != null && max < 1) throw ApiException.badRequest("The use limit must be at least 1.");
        UUID org = Req.uuid(b, "orgId");
        String note = Req.str(b, "note");
        if (note != null && note.length() > 200) throw ApiException.badRequest("Keep the note under 200 characters.");
        BigDecimal amountOff = amount;
        try {
            as(org, () -> {
                db.update("""
                        insert into leasing.boost_promo (code, org_id, percent_off, amount_off, valid_from, valid_until, max_uses, note, created_by)
                        values (?,?,?,?,?,?,?,?,?)""", code, org, pct, amountOff, from, until, max, note, by);
                audit.record("BoostPromo", UUID.nameUUIDFromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8)), "BOOST_PROMO_CREATED", null, null,
                        Map.of("code", code, "by", by, "company", org == null ? "any" : org.toString()));
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw ApiException.conflict("There is already a code called " + code + ".");
        }
        return Map.of("code", code);
    }

    /** Switches a promo code on or off. A code that has been used is never deleted, so past boosts still explain their price. */
    public Map<String, Object> setPromoActive(String code, boolean active, String by) {
        as(null, () -> {
            if (db.update("update leasing.boost_promo set active = ? where code = ?", active, code.toUpperCase()) == 0) throw ApiException.notFound("Promo code");
            return null;
        });
        log.info("Boost promo {} {} by {}", code, active ? "switched on" : "switched off", by);
        return Map.of("code", code.toUpperCase(), "active", active);
    }

    private static LocalDate date(Map<String, Object> b, String key) {
        String v = Req.str(b, key);
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.trim());
        } catch (DateTimeParseException e) {
            throw ApiException.badRequest("\"" + key + "\" must be a date such as 2026-12-31.");
        }
    }

    // ----------------------------------------------------------------------------------------------- helpers

    private void voidInvoice(UUID org, UUID invoiceId, String reason) {
        try {
            UserContextHolder.runAsSystem(org, () -> client.post(ServiceClient.Service.IDENTITY, "/internal/billing/invoices/" + invoiceId + "/void", Map.of("reason", reason)));
        } catch (RuntimeException e) {
            log.warn("Could not void boost invoice {}: {}", invoiceId, e.getMessage());
        }
    }

    private void tell(UUID org, String title, String body, String link, String dedupe) {
        try {
            UserContextHolder.runAsSystem(org, () -> { notifier.sendAlert("ROLE", "ORG_ADMIN", title, body, link, "NORMAL", "GENERAL", dedupe); return null; });
        } catch (RuntimeException e) {
            log.warn("Boost notice not sent: {}", e.getMessage());
        }
    }
}
