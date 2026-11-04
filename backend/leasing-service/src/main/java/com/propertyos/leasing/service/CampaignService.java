package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;

/**
 * A company's marketing campaigns: one per channel. Staff create, edit, pause and resume them and rotate their keys. Leads and applications that arrive
 * through a campaign (an ad platform posting to its signed inbound address, or a visitor following its tracking link) are tagged with it, so the
 * list shows what each campaign brought in and what each lead cost. The signing secret is returned once, when it is made or rotated, and never again.
 *
 * <p>Needs the CAMPAIGNS plan feature and the CAMPAIGN permissions; the controller checks the permission and this class checks the plan.</p>
 */
@Service
public class CampaignService {
    static final Set<String> CHANNELS = Set.of("FACEBOOK_ADS", "GOOGLE_ADS", "ZILLOW", "APARTMENTS", "EMAIL", "OTHER");

    private final Db db;
    private final AuditService audit;
    private final ConfigClient config;
    private final CampaignInbound inbound;
    private final ObjectMapper mapper;
    private final Notifier notifier;
    private final TransactionTemplate tx;

    public CampaignService(Db db, AuditService audit, ConfigClient config, CampaignInbound inbound, ObjectMapper mapper, Notifier notifier, PlatformTransactionManager tm) {
        this.db = db;
        this.audit = audit;
        this.config = config;
        this.inbound = inbound;
        this.mapper = mapper;
        this.notifier = notifier;
        this.tx = new TransactionTemplate(tm);
    }

    private void requirePlan() {
        if (!config.featureEnabled("CAMPAIGNS")) throw ApiException.forbidden("Marketing campaigns aren't included in your company's plan. Ask PropertyOS about adding them.");
    }

    /** The columns staff may see. The secret is never one of them. */
    private static final String VISIBLE = """
            c.id, c.name, c.channel, c.status, c.spend, c.starts_on, c.ends_on, c.public_key, c.tracking_code, c.created_by, c.created_at, c.updated_at,
            case when c.status = 'PAUSED' then 'PAUSED' when c.ends_on is not null and c.ends_on < current_date then 'ENDED'
                 when c.starts_on is not null and c.starts_on > current_date then 'SCHEDULED' else 'ACTIVE' end as state,
            (select count(*) from leasing.lead l where l.campaign_id = c.id) as leads,
            (select count(*) from leasing.application a where a.campaign_id = c.id) as applications,
            (select count(*) from leasing.showing s where s.campaign_id = c.id) as showings,
            (select count(distinct le.id) from leasing.lease le join leasing.application a on a.id = le.application_id
              where a.campaign_id = c.id and le.status in ('TENANT_SIGNED', 'ACTIVE', 'RENEWAL_OFFERED', 'NOTICE_GIVEN', 'ENDED')) as leases,
            (select count(*) from (select lower(email) from leasing.lead where campaign_id = c.id
                                   union select lower(applicant_email) from leasing.application where campaign_id = c.id
                                   union select lower(prospect_email) from leasing.showing where campaign_id = c.id) p) as people""";

    /** The company's campaigns, newest first, with what each has brought in and the cost per lead. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> list() {
        requirePlan();
        List<Map<String, Object>> rows = db.page("select " + VISIBLE + " from leasing.campaign c order by c.created_at desc");
        rows.forEach(CampaignService::costPerLead);
        return rows;
    }

    static void costPerLead(Map<String, Object> r) {
        long leads = ((Number) r.get("leads")).longValue();
        BigDecimal spend = (BigDecimal) r.get("spend");
        r.put("cost_per_lead", leads > 0 && spend.signum() > 0 ? spend.divide(BigDecimal.valueOf(leads), 2, RoundingMode.HALF_UP) : null);
        // Conversion is signed leases out of the different people who came in (a person with a lead and an application counts once).
        long people = ((Number) r.get("people")).longValue(), leases = ((Number) r.get("leases")).longValue();
        r.put("conversion", people > 0 ? BigDecimal.valueOf(leases * 100.0 / people).setScale(1, RoundingMode.HALF_UP) : null);
    }

    private Map<String, Object> one(UUID id) {
        Map<String, Object> r = db.one("Campaign", "select " + VISIBLE + " from leasing.campaign c where c.id = ?", id);
        costPerLead(r);
        return r;
    }

    private static LocalDate date(Map<String, Object> b, String key) {
        try {
            return Req.date(b, key);
        } catch (ApiException e) {
            throw ApiException.badRequest("\"" + key + "\" must be a date such as 2026-12-31.");
        }
    }

    private static BigDecimal spend(Map<String, Object> b) {
        if (b.get("spend") == null || String.valueOf(b.get("spend")).isBlank()) return null;
        try {
            BigDecimal s = new BigDecimal(String.valueOf(b.get("spend"))).setScale(2, RoundingMode.HALF_UP);
            if (s.signum() < 0 || s.compareTo(new BigDecimal("100000000")) > 0) throw new NumberFormatException();
            return s;
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Enter the amount spent as a number, for example 1500.00.");
        }
    }

    private static String channel(String v) {
        String c = v == null ? "" : v.trim().toUpperCase();
        if (!CHANNELS.contains(c)) throw ApiException.badRequest("Choose a channel: Facebook ads, Google ads, Zillow, Apartments.com, email or other.");
        return c;
    }

    /**
     * Makes a campaign.
     *
     * @param b name, channel, optional spend, startsOn, endsOn
     * @return the campaign plus its signing {@code secret}, shown only this once
     * @throws ApiException (400) for a bad field, (403) when the plan leaves campaigns out, (409) at the company's campaign limit
     */
    @Transactional
    public Map<String, Object> create(Map<String, Object> b) {
        requirePlan();
        UserContext ctx = UserContextHolder.require();
        String name = Req.required(b, "name").trim();
        if (name.length() > 160) throw ApiException.badRequest("Keep the name under 160 characters.");
        String channel = channel(Req.str(b, "channel"));
        BigDecimal spend = Optional.ofNullable(spend(b)).orElse(BigDecimal.ZERO);
        LocalDate from = date(b, "startsOn"), to = date(b, "endsOn");
        if (from != null && to != null && to.isBefore(from)) throw ApiException.badRequest("The campaign ends before it starts.");
        JsonNode cfg = config.forOrg(ctx.orgId());
        int max = (cfg.has("campaigns") ? cfg.get("campaigns") : config.defaults().path("campaigns")).path("maxPerCompany").asInt(100);
        if (db.value(Long.class, "select count(*) from leasing.campaign") >= max) throw ApiException.conflict("You have reached the limit of " + max + " campaigns. Remove or reuse one first.");
        UUID id = UUID.randomUUID();
        String secret = CampaignSecurity.newSecret();
        db.update("""
                insert into leasing.campaign (id, org_id, name, channel, status, spend, starts_on, ends_on, public_key, secret, tracking_code, created_by)
                values (?,?,?,?,'ACTIVE',?,?,?,?,?,?,?)""",
                id, ctx.orgId(), name, channel, spend, from, to, CampaignSecurity.newPublicKey(), secret, CampaignSecurity.newTrackingCode(), ctx.name());
        audit.record("Campaign", id, "CAMPAIGN_CREATED", null, "ACTIVE", Map.of("name", name, "channel", channel, "by", ctx.name()));
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("secret", secret);
        return out;
    }

    /** Changes a campaign's name, channel, spend or dates. Only the fields present change. */
    @Transactional
    public Map<String, Object> update(UUID id, Map<String, Object> b) {
        requirePlan();
        Map<String, Object> cur = one(id);
        String name = b.containsKey("name") ? Req.required(b, "name").trim() : (String) cur.get("name");
        if (name.length() > 160) throw ApiException.badRequest("Keep the name under 160 characters.");
        String channel = b.containsKey("channel") ? channel(Req.str(b, "channel")) : (String) cur.get("channel");
        BigDecimal spend = b.containsKey("spend") ? Optional.ofNullable(spend(b)).orElse(BigDecimal.ZERO) : (BigDecimal) cur.get("spend");
        LocalDate from = b.containsKey("startsOn") ? date(b, "startsOn") : toDate(cur.get("starts_on"));
        LocalDate to = b.containsKey("endsOn") ? date(b, "endsOn") : toDate(cur.get("ends_on"));
        if (from != null && to != null && to.isBefore(from)) throw ApiException.badRequest("The campaign ends before it starts.");
        db.update("update leasing.campaign set name = ?, channel = ?, spend = ?, starts_on = ?, ends_on = ?, updated_at = now() where id = ?", name, channel, spend, from, to, id);
        audit.record("Campaign", id, "CAMPAIGN_UPDATED", null, null, Map.of("by", UserContextHolder.require().name()));
        return one(id);
    }

    private static LocalDate toDate(Object v) {
        return v == null ? null : v instanceof java.sql.Date d ? d.toLocalDate() : LocalDate.parse(String.valueOf(v));
    }

    /** One campaign with its numbers and its recent activity: the newest leads, tours and applications that came in through it. */
    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        requirePlan();
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("activity", db.list("""
                select * from (
                  select 'LEAD' as kind, l.id, l.created_at, l.name, l.email, li.title as listing_title, l.status, l.source
                  from leasing.lead l left join leasing.listing li on li.id = l.listing_id where l.campaign_id = ?
                  union all
                  select 'APPLICATION', a.id, a.created_at, a.applicant_name, a.applicant_email, li.title, a.status, a.source
                  from leasing.application a join leasing.listing li on li.id = a.listing_id where a.campaign_id = ?
                  union all
                  select 'SHOWING', sh.id, sh.created_at, sh.prospect_name, sh.prospect_email, li.title, sh.status, sh.source
                  from leasing.showing sh join leasing.listing li on li.id = sh.listing_id where sh.campaign_id = ?
                ) x order by created_at desc limit 15""", id, id, id));
        return out;
    }

    /**
     * Leads over time, for the charts: one campaign (when {@code campaignId} is given) or the company's busiest campaigns side by side. Buckets are days
     * or weeks; empty buckets are filled with zero so the chart has no gaps.
     *
     * @return {@code keys} (one entry per series: key, campaign_id, name) and {@code rows} (label plus a count under each key)
     */
    @Transactional(readOnly = true)
    public Map<String, Object> series(UUID campaignId, int days, boolean weekly) {
        requirePlan();
        int span = Math.max(7, Math.min(days, 180));
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(span - 1L);
        if (weekly) from = from.minusDays(from.getDayOfWeek().getValue() - 1L); // start on the Monday
        List<Map<String, Object>> chosen = campaignId != null
                ? db.list("select id, name from leasing.campaign where id = ?", campaignId)
                : db.list("""
                        select c.id, c.name from leasing.campaign c
                        where exists (select 1 from leasing.lead l where l.campaign_id = c.id and l.created_at >= ?::date)
                        order by (select count(*) from leasing.lead l where l.campaign_id = c.id and l.created_at >= ?::date) desc, c.name limit 5""", from.toString(), from.toString());
        if (campaignId != null && chosen.isEmpty()) throw ApiException.notFound("Campaign");
        List<Map<String, Object>> keys = new ArrayList<>();
        Map<UUID, String> keyOf = new HashMap<>();
        for (int i = 0; i < chosen.size(); i++) {
            UUID cid = (UUID) chosen.get(i).get("id");
            String key = "c" + i;
            keyOf.put(cid, key);
            Map<String, Object> k = new LinkedHashMap<>();
            k.put("key", key);
            k.put("campaign_id", cid);
            k.put("name", chosen.get(i).get("name"));
            keys.add(k);
        }
        // Count per campaign and bucket start. The bucket is the day, or the Monday of the week.
        Map<String, Integer> counts = new HashMap<>();
        if (!keyOf.isEmpty()) {
            String bucket = weekly ? "date_trunc('week', l.created_at)::date" : "l.created_at::date";
            for (Map<String, Object> r : db.list("select l.campaign_id, " + bucket + " as bucket, count(*) as n from leasing.lead l where l.campaign_id::text = any (string_to_array(?, ',')) and l.created_at >= ?::date group by 1, 2",
                    String.join(",", keyOf.keySet().stream().map(UUID::toString).toList()), from.toString())) {
                LocalDate d = r.get("bucket") instanceof java.sql.Date sd ? sd.toLocalDate() : LocalDate.parse(String.valueOf(r.get("bucket")));
                counts.put(keyOf.get((UUID) r.get("campaign_id")) + "|" + d, ((Number) r.get("n")).intValue());
            }
        }
        java.time.format.DateTimeFormatter label = java.time.format.DateTimeFormatter.ofPattern("MMM d", Locale.US);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (LocalDate d = from; !d.isAfter(today); d = d.plusDays(weekly ? 7 : 1)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("label", label.format(d));
            int total = 0;
            for (Map<String, Object> k : keys) {
                int n = counts.getOrDefault(k.get("key") + "|" + d, 0);
                row.put((String) k.get("key"), n);
                total += n;
            }
            row.put("total", total);
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("keys", keys);
        out.put("rows", rows);
        return out;
    }

    private static final String[] FIRST = {"Maya", "Jordan", "Elena", "Marcus", "Priya", "Tom", "Hannah", "Devon", "Sofia", "Liam"};
    private static final String[] LAST = {"Patel", "Brooks", "Garcia", "Webb", "Nair", "Alvarez", "Kim", "Carter", "Rossi", "Okafor"};
    private static final String[] ASKS = {
            "Hi, is this home still available? I'd love to tour it this weekend.",
            "Does the rent include parking? I'm hoping to move in next month.",
            "We have a small dog. Is that allowed, and can we book a showing on Saturday?",
            "Could you tell me about the lease terms and the deposit? I can move in on the first."};

    /**
     * Sends a realistic sample lead through this campaign's own signed inbound address, so the whole path can be shown live: signature check, tagging,
     * de-duplication, the new lead in Leads, and the notification. The campaign secret never leaves the server: it is read here and used to sign. The person
     * who pressed the button is also notified, so they see the notification even if they are not a leasing agent.
     *
     * @return the sample lead as the inbound address reported it, plus the name and home used
     * @throws ApiException (403) when the plan leaves campaigns out, (404) for another company's campaign, (409) when the campaign is paused or ended or the company has no live home
     */
    public Map<String, Object> sendTestLead(UUID id) {
        requirePlan();
        UserContext ctx = UserContextHolder.require();
        // Read in the signed-in person's own transaction, so another company's campaign is simply not found. The post itself runs outside it, as a visitor's would.
        Map<String, Object> c = tx.execute(st -> db.find("select id, name, public_key, secret from leasing.campaign where id = ?", id)).orElseThrow(() -> ApiException.notFound("Campaign"));
        // A campaign promotes particular homes, so the sample asks about one of the homes it already gets the most leads for (a leasing agent who only covers some
        // properties then sees it). With no leads yet, any live home will do.
        Map<String, Object> home = tx.execute(st -> db.find("""
                select l.id, l.title from leasing.listing l
                where l.status = 'LIVE' and l.id in (select listing_id from leasing.lead where campaign_id = ? and listing_id is not null group by listing_id order by count(*) desc limit 3)
                order by random() limit 1""", id).or(() -> db.find("select id, title from leasing.listing where status = 'LIVE' order by random() limit 1")))
                .orElseThrow(() -> ApiException.conflict("Publish a listing first, so the sample lead has a home to ask about."));
        Random rnd = new Random();
        String first = FIRST[rnd.nextInt(FIRST.length)], last = LAST[rnd.nextInt(LAST.length)];
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "lead");
        body.put("name", first + " " + last);
        body.put("email", (first + "." + last + (100 + rnd.nextInt(900)) + "@example.com").toLowerCase());
        body.put("phone", "512555" + String.format("%04d", rnd.nextInt(10000)));
        body.put("listingId", home.get("id").toString());
        body.put("message", ASKS[rnd.nextInt(ASKS.length)]);
        byte[] bytes;
        try {
            bytes = mapper.writeValueAsBytes(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        Map<String, Object> result = inbound.receive((String) c.get("public_key"), "sha256=" + CampaignSecurity.sign((String) c.get("secret"), bytes), bytes);
        audit.record("Campaign", id, "CAMPAIGN_TEST_LEAD", null, null, Map.of("by", ctx.name(), "lead", String.valueOf(result.get("id"))));
        try {
            notifier.toUser(ctx.userId(), "Test lead received", body.get("name") + " asked about " + home.get("title") + " through " + c.get("name") + ".", "/leasing/leads");
        } catch (RuntimeException ignored) {
            // the demo still worked; a missing personal notice must not turn it into an error
        }
        Map<String, Object> out = new LinkedHashMap<>(result);
        out.put("name", body.get("name"));
        out.put("email", body.get("email"));
        out.put("listing_title", home.get("title"));
        return out;
    }

    /** Pauses a campaign (its inbound address and tracking link stop tagging new leads) or resumes it. */
    @Transactional
    public Map<String, Object> setPaused(UUID id, boolean paused) {
        requirePlan();
        one(id); // 404 for another company's campaign
        db.update("update leasing.campaign set status = ?, updated_at = now() where id = ?", paused ? "PAUSED" : "ACTIVE", id);
        audit.record("Campaign", id, paused ? "CAMPAIGN_PAUSED" : "CAMPAIGN_RESUMED", paused ? "ACTIVE" : "PAUSED", paused ? "PAUSED" : "ACTIVE", Map.of("by", UserContextHolder.require().name()));
        return one(id);
    }

    /**
     * Replaces the campaign's signing secret; the old one stops working at once. With {@code newAddress} the inbound address changes too, so the old address
     * stops working as well. The tracking link never changes, so printed or published links keep working.
     *
     * @return the campaign plus the new {@code secret}, shown only this once
     */
    @Transactional
    public Map<String, Object> rotate(UUID id, boolean newAddress) {
        requirePlan();
        one(id);
        String secret = CampaignSecurity.newSecret();
        if (newAddress) db.update("update leasing.campaign set secret = ?, public_key = ?, updated_at = now() where id = ?", secret, CampaignSecurity.newPublicKey(), id);
        else db.update("update leasing.campaign set secret = ?, updated_at = now() where id = ?", secret, id);
        audit.record("Campaign", id, "CAMPAIGN_KEY_ROTATED", null, null, Map.of("by", UserContextHolder.require().name(), "newAddress", newAddress));
        Map<String, Object> out = new LinkedHashMap<>(one(id));
        out.put("secret", secret);
        return out;
    }
}
