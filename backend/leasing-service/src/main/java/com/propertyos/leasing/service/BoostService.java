package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.*;

/**
 * Paid boosts for live listings. A company picks a period; an invoice for it is raised on the platform billing side (the identity service) and the
 * boost waits as PENDING. When that invoice is paid in full the boost becomes ACTIVE for the period, starting at the moment of payment. A declined
 * payment never reaches this service, so nothing starts. A timer expires boosts at the end of their period, warns the company 24 hours before, and
 * cancels boosts that were never paid for.
 *
 * <p>What a boost earns in search (the ranking bonus and the cap on boosted homes per page) is in {@link ListingRanking}.</p>
 */
@Service
public class BoostService {
    private static final Logger log = LoggerFactory.getLogger(BoostService.class);

    private final Db db;
    private final ConfigClient config;
    private final ServiceClient client;
    private final Notifier notifier;
    private final AuditService audit;
    private final TransactionTemplate tx;

    public BoostService(Db db, ConfigClient config, ServiceClient client, Notifier notifier, AuditService audit, PlatformTransactionManager tm) {
        this.db = db;
        this.config = config;
        this.client = client;
        this.notifier = notifier;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
    }

    private ListingRanking.Boost settings(UUID org) {
        JsonNode cfg = org == null ? config.defaults() : config.forOrg(org);
        return ListingRanking.Boost.of(cfg.has("boost") ? cfg.get("boost") : config.defaults().path("boost"));
    }

    /** Whether this company can buy boosts: on sale platform-wide, and the BOOST feature is part of its plan (and not switched off for it). */
    private boolean available(ListingRanking.Boost b) {
        return b.enabled() && config.featureEnabled("BOOST");
    }

    private String planOf(UUID org) {
        return config.forOrg(org).path("plan").asText("");
    }

    /** The price this company pays for a period, before any promo code: its agreed price if it has one, otherwise the standard price less its plan discount. */
    @Transactional(readOnly = true)
    BoostPricing.Quote basePrice(UUID org, ListingRanking.Boost b, int days) {
        BigDecimal list = b.prices().get(days);
        if (list == null) throw ApiException.badRequest("Choose one of the boost periods on offer.");
        List<Map<String, Object>> agreed = db.list("select price from leasing.boost_company_price where org_id = ? and days = ?", org, days);
        String plan = planOf(org);
        return BoostPricing.quote(list, agreed.isEmpty() ? null : (BigDecimal) agreed.get(0).get("price"), b.discountFor(plan), plan, null);
    }

    /** Finds a promo code this company may use right now. With {@code consume} it also counts one use, in the caller's transaction, so a failed purchase gives it back. */
    private BoostPricing.Promo promo(String code, UUID org, boolean consume) {
        if (code == null || code.isBlank()) return null;
        String c = code.trim().toUpperCase();
        String usable = " code = ? and active and (org_id is null or org_id = ?) and (valid_from is null or valid_from <= current_date)"
                + " and (valid_until is null or valid_until >= current_date) and (max_uses is null or uses < max_uses)";
        List<Map<String, Object>> found = db.list("select code, percent_off, amount_off from leasing.boost_promo where" + usable, c, org);
        if (found.isEmpty() || (consume && db.update("update leasing.boost_promo set uses = uses + 1 where" + usable, c, org) == 0))
            throw ApiException.badRequest("That code isn't valid for your company, or it has expired.");
        Map<String, Object> f = found.get(0);
        return new BoostPricing.Promo(c, f.get("percent_off") == null ? null : ((Number) f.get("percent_off")).intValue(), (BigDecimal) f.get("amount_off"));
    }

    /**
     * What a period would cost this company, with an optional promo code, in words and numbers. Nothing is bought and no code is used up.
     *
     * @throws ApiException (400) for a period that is not offered or a code that is not valid
     */
    @Transactional(readOnly = true)
    public Map<String, Object> quote(int days, String code) {
        UUID org = UserContextHolder.orgId();
        ListingRanking.Boost b = settings(org);
        BoostPricing.Quote base = basePrice(org, b, days);
        BoostPricing.Promo promo = promo(code, org, false);
        BoostPricing.Quote q = promo == null ? base : BoostPricing.quote(base.listPrice(), agreedOrNull(org, days), b.discountFor(planOf(org)), planOf(org), promo);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("days", days);
        out.put("list_price", q.listPrice());
        out.put("total", q.total());
        out.put("saving", q.saving());
        out.put("notes", q.notes());
        return out;
    }

    private BigDecimal agreedOrNull(UUID org, int days) {
        List<Map<String, Object>> agreed = db.list("select price from leasing.boost_company_price where org_id = ? and days = ?", org, days);
        return agreed.isEmpty() ? null : (BigDecimal) agreed.get(0).get("price");
    }

    /** The periods on offer and what the boost does, for the Boost dialog. Each period carries this company's own price. */
    @Transactional(readOnly = true)
    public Map<String, Object> options() {
        UUID org = UserContextHolder.orgId();
        ListingRanking.Boost b = settings(org);
        List<Map<String, Object>> periods = new ArrayList<>();
        for (Integer days : b.prices().keySet()) {
            BoostPricing.Quote q = basePrice(org, b, days);
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("days", days);
            p.put("price", q.total());
            p.put("list_price", q.listPrice());
            p.put("notes", q.notes());
            periods.add(p);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("available", available(b));
        out.put("periods", periods);
        out.put("bonus_points", b.bonusPoints());
        out.put("max_share_percent", b.maxSharePercent());
        return out;
    }

    /** A listing's boosts, newest first, as the signed-in person may see them (their own company's). */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> forListing(UUID listingId) {
        db.one("Listing", "select id from leasing.listing where id = ?", listingId); // 404 when it is not this company's
        return db.list("select id, days, starts_at, ends_at, amount, status, invoice_id from leasing.listing_boost where listing_id = ? order by created_at desc limit 10", listingId);
    }

    /**
     * Starts the purchase of a boost: raises the invoice and records the boost as PENDING. The company then pays the invoice from its Subscription page.
     *
     * @param days one of the periods on offer
     * @throws ApiException (400) for a period that is not offered, (404) when the listing is not the company's, (409) when the listing is not live or already has a boost
     */
    @Transactional
    public Map<String, Object> request(UUID listingId, int days, String promoCode) {
        UserContext ctx = UserContextHolder.require();
        ListingRanking.Boost settings = settings(ctx.orgId());
        if (!available(settings)) throw ApiException.forbidden("Listing boosts are not available for your company right now. Ask PropertyOS about adding them to your plan.");
        BoostPricing.Quote base = basePrice(ctx.orgId(), settings, days);
        Map<String, Object> l = db.one("Listing", "select id, title, status, property_id, owner_id from leasing.listing where id = ?", listingId);
        if (!"LIVE".equals(l.get("status"))) throw ApiException.conflict("Only a live listing can be boosted. Publish it first.");
        if (db.value(Long.class, "select count(*) from leasing.listing_boost where listing_id = ? and status in ('PENDING', 'ACTIVE')", listingId) > 0)
            throw ApiException.conflict("This listing already has a boost that is running or waiting for payment.");

        // The code is counted as used here; if anything below fails the whole purchase rolls back and the use is given back.
        BoostPricing.Promo promo = promo(promoCode, ctx.orgId(), true);
        BoostPricing.Quote quote = promo == null ? base
                : BoostPricing.quote(base.listPrice(), agreedOrNull(ctx.orgId(), days), settings.discountFor(planOf(ctx.orgId())), planOf(ctx.orgId()), promo);
        BigDecimal price = quote.total();
        String description = "Listing boost, " + days + " days: " + l.get("title") + (quote.notes().isEmpty() ? "" : " (standard $" + quote.listPrice().toPlainString() + "; " + quote.summary() + ")");
        JsonNode inv = UserContextHolder.runAsSystem(ctx.orgId(), () -> client.post(ServiceClient.Service.IDENTITY, "/internal/billing/invoices",
                Map.of("orgId", ctx.orgId().toString(), "kind", "BOOST", "amount", price.toPlainString(), "description", description.length() > 290 ? description.substring(0, 290) : description,
                        "dueInDays", settings.unpaidExpiryDays())));
        UUID invoiceId = UUID.fromString(inv.path("id").asText());
        UUID id = UUID.randomUUID();
        try {
            db.update("""
                    insert into leasing.listing_boost (id, org_id, listing_id, property_id, owner_id, days, starts_at, ends_at, amount, status, invoice_id, created_by,
                                                       list_price, discount_note, promo_code)
                    values (?,?,?,?,?,?, now(), now() + make_interval(days => ?), ?, 'PENDING', ?, ?, ?,?,?)""",
                    id, ctx.orgId(), listingId, l.get("property_id"), l.get("owner_id"), days, days, price, invoiceId, ctx.name(),
                    quote.listPrice(), quote.notes().isEmpty() ? null : quote.summary(), promo == null ? null : promo.code());
        } catch (RuntimeException e) {
            voidInvoice(ctx.orgId(), invoiceId, "The boost could not be recorded");
            throw e;
        }
        audit.record("Listing", listingId, "BOOST_REQUESTED", null, "PENDING", Map.of("days", days, "amount", price.toString(), "invoice", inv.path("number").asText(), "by", ctx.name()));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("status", "PENDING");
        out.put("days", days);
        out.put("amount", price);
        out.put("list_price", quote.listPrice());
        out.put("notes", quote.notes());
        out.put("invoice_id", invoiceId);
        out.put("invoice_number", inv.path("number").asText());
        return out;
    }

    /**
     * Runs when a platform invoice has been paid in full: the boost bought with it, if any, becomes ACTIVE for its period starting now, and the
     * company is told. Safe to run twice for the same invoice.
     */
    @Transactional
    public void onInvoicePaid(UUID invoiceId) {
        List<Map<String, Object>> started = db.list("""
                update leasing.listing_boost set status = 'ACTIVE', starts_at = now(), ends_at = now() + make_interval(days => days), updated_at = now()
                where invoice_id = ? and status = 'PENDING' returning id, org_id, listing_id, days, ends_at""", invoiceId);
        for (Map<String, Object> b : started) {
            UUID org = (UUID) b.get("org_id");
            audit.record("Listing", (UUID) b.get("listing_id"), "BOOST_STARTED", "PENDING", "ACTIVE", Map.of("days", b.get("days"), "invoice", invoiceId.toString()));
            tell(org, "Your listing boost is live", "Your " + b.get("days") + "-day boost has started and runs until " + day(b.get("ends_at")) + ". It shows as Sponsored in search.",
                    "/leasing/listings/" + b.get("listing_id"), "boost-start-" + b.get("id"));
        }
    }

    /**
     * Every 10 minutes: expires boosts whose period is over, warns the company 24 hours before a boost ends (once), and cancels boosts that were
     * never paid for, voiding their invoice. Each step is a single statement per boost, so running it twice, or on two servers, does no harm.
     */
    @Scheduled(fixedDelayString = "${propertyos.boost.tick-ms:600000}", initialDelay = 60000)
    public void tick() {
        try {
            UserContextHolder.runAsSystem(null, () -> { runTick(); return null; });
        } catch (RuntimeException e) {
            log.warn("Boost timer failed: {}", e.getMessage());
        }
    }

    void runTick() {
        List<Map<String, Object>> ending = tx.execute(st -> {
            db.update("update leasing.listing_boost set status = 'EXPIRED', updated_at = now() where status = 'ACTIVE' and ends_at <= now()");
            return db.list("""
                    update leasing.listing_boost set ending_notified = true, updated_at = now()
                    where status = 'ACTIVE' and not ending_notified and ends_at > now() and ends_at <= now() + interval '24 hours'
                    returning id, org_id, listing_id, ends_at""");
        });
        for (Map<String, Object> b : ending == null ? List.<Map<String, Object>>of() : ending)
            tell((UUID) b.get("org_id"), "Your listing boost ends within 24 hours", "Your boost ends on " + day(b.get("ends_at")) + ". Boost the listing again to stay Sponsored.",
                    "/leasing/listings/" + b.get("listing_id"), "boost-ending-" + b.get("id"));

        List<UUID> orgs = tx.execute(st -> db.list("select distinct org_id from leasing.listing_boost where status = 'PENDING'").stream().map(r -> (UUID) r.get("org_id")).toList());
        for (UUID org : orgs == null ? List.<UUID>of() : orgs) {
            int days = settings(org).unpaidExpiryDays();
            List<Map<String, Object>> stale = tx.execute(st -> db.list("""
                    update leasing.listing_boost set status = 'EXPIRED', updated_at = now()
                    where org_id = ? and status = 'PENDING' and created_at < now() - make_interval(days => ?) returning id, invoice_id""", org, days));
            for (Map<String, Object> b : stale == null ? List.<Map<String, Object>>of() : stale) voidInvoice(org, (UUID) b.get("invoice_id"), "The boost was not paid for within " + days + " days");
        }
    }

    /** How many boosts are running now, across all companies: the operator's figure. */
    @Transactional(readOnly = true)
    public long activeCount() {
        return db.value(Long.class, "select count(*) from leasing.listing_boost where status = 'ACTIVE' and starts_at <= now() and ends_at > now()");
    }

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
            log.warn("Boost notice not sent: {}", e.getMessage()); // an alert that cannot be sent must not undo the boost
        }
    }

    private static String day(Object ts) {
        return ts == null ? "" : String.valueOf(ts).replace('T', ' ').substring(0, 10);
    }
}
