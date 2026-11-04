package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.SuspendedOrgs;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The public inbound address of a campaign: {@code POST /api/leasing/public/inbound/{campaignKey}}. An ad platform or form tool posts a lead or an
 * application as JSON, signed with the campaign's secret. Nothing is trusted until the signature checks out, and a campaign can only ever create
 * leads and applications for homes of its own company.
 *
 * <p>Order of checks: the per-campaign rate limit, the key (unknown is 404), the signature (missing or wrong is 401), the company's plan and standing,
 * the campaign's state and dates, then the payload. A person who has already asked about the same home is not entered twice.</p>
 */
@Service
public class CampaignInbound {
    private static final Logger log = LoggerFactory.getLogger(CampaignInbound.class);
    static final int MAX_BODY_BYTES = 32 * 1024;

    private final Db db;
    private final LeadService leads;
    private final PublicService publicService;
    private final ConfigClient config;
    private final Notifier notifier;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private SuspendedOrgs suspended;

    /** Posts seen per campaign key in the current minute: {minute, count}. */
    private final Map<String, long[]> seen = new ConcurrentHashMap<>();

    public CampaignInbound(Db db, LeadService leads, PublicService publicService, ConfigClient config, Notifier notifier, ObjectMapper mapper, PlatformTransactionManager tm) {
        this.db = db;
        this.leads = leads;
        this.publicService = publicService;
        this.config = config;
        this.notifier = notifier;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(tm);
    }

    @Autowired(required = false)
    void setSuspended(SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    /** True when this key has had more than {@code limit} posts this minute. Counts the call. */
    boolean overLimit(String key, int limit, long nowMinute) {
        long[] w = seen.compute(key, (k, cur) -> cur == null || cur[0] != nowMinute ? new long[]{nowMinute, 1} : new long[]{cur[0], cur[1] + 1});
        if (seen.size() > 5000) seen.entrySet().removeIf(e -> e.getValue()[0] != nowMinute); // keys that stopped posting are forgotten
        return w[1] > limit;
    }

    /**
     * Receives one signed post.
     *
     * @param key       the campaign's public key from the address
     * @param signature the {@code X-Signature} header
     * @param body      the exact bytes that were signed
     * @return what was created: id, type, whether the person was already known
     */
    public Map<String, Object> receive(String key, String signature, byte[] body) {
        if (body.length > MAX_BODY_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "TOO_LARGE", "That request is too large.");
        int limit = config.defaults().path("campaigns").path("inboundPerMinute").asInt(120);
        if (overLimit(key, limit, System.currentTimeMillis() / 60_000)) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many requests for this campaign. Try again in a minute.");

        Map<String, Object> campaign = UserContextHolder.runAsSystem(null, () -> tx.execute(st ->
                db.find("select id, org_id, name, channel, status, starts_on, ends_on, secret from leasing.campaign where public_key = ?", key))).orElseThrow(() -> ApiException.notFound("Campaign"));
        if (!CampaignSecurity.verify((String) campaign.get("secret"), signature, body))
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_SIGNATURE", "The signature is missing or does not match. Sign the exact request body with the campaign secret (HMAC-SHA256) and send it in X-Signature.");

        UUID org = (UUID) campaign.get("org_id");
        if (suspended != null && suspended.contains(org)) throw ApiException.notFound("Campaign");
        if (!config.forOrg(org).path("features").path("CAMPAIGNS").asBoolean(true))
            throw ApiException.forbidden("Campaigns are not enabled for this company.");

        JsonNode json;
        try {
            json = mapper.readTree(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw ApiException.badRequest("The body must be JSON.");
        }
        if (json == null || !json.isObject()) throw ApiException.badRequest("The body must be a JSON object.");

        return UserContextHolder.runAsSystem(null, () -> tx.execute(st -> create(campaign, json)));
    }

    private static String text(JsonNode j, String field, int max) {
        JsonNode n = j.path(field);
        if (n.isMissingNode() || n.isNull()) return null;
        if (!n.isTextual()) throw ApiException.badRequest("\"" + field + "\" must be text.");
        String s = n.asText().trim();
        if (s.length() > max) throw ApiException.badRequest("\"" + field + "\" is too long (at most " + max + " characters).");
        return s.isEmpty() ? null : s;
    }

    private Map<String, Object> create(Map<String, Object> campaign, JsonNode j) {
        if (!"ACTIVE".equals(campaign.get("status")) || outsideDates(campaign))
            throw ApiException.conflict("This campaign isn't accepting leads right now.");
        UUID org = (UUID) campaign.get("org_id"), campaignId = (UUID) campaign.get("id");
        String type = Optional.ofNullable(text(j, "type", 20)).orElse("lead").toLowerCase();
        if (!type.equals("lead") && !type.equals("application")) throw ApiException.badRequest("\"type\" is lead or application.");
        String name = text(j, "name", 160);
        String email = text(j, "email", 200);
        if (name == null) throw ApiException.badRequest("\"name\" is required.");
        if (email == null || !email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("\"email\" must be a valid email address.");
        String phone = text(j, "phone", 40), message = text(j, "message", 2000);

        // The home: a listing id or a unit id, and it must be a live home of this campaign's own company.
        UUID listingId;
        try {
            String l = text(j, "listingId", 40), u = text(j, "unitId", 40);
            if (l == null && u == null) throw ApiException.badRequest("Say which home: \"listingId\" or \"unitId\".");
            listingId = l != null
                    ? db.find("select id from leasing.listing where id = ? and org_id = ? and status = 'LIVE'", UUID.fromString(l), org).map(r -> (UUID) r.get("id")).orElse(null)
                    : db.find("select id from leasing.listing where unit_id = ? and org_id = ? and status = 'LIVE'", UUID.fromString(u), org).map(r -> (UUID) r.get("id")).orElse(null);
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("\"listingId\" and \"unitId\" must be ids.");
        }
        if (listingId == null) throw ApiException.notFound("Live home");

        String channel = (String) campaign.get("channel");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("type", type);
        out.put("campaign", campaign.get("name"));
        if (type.equals("lead")) {
            Optional<Map<String, Object>> dup = db.find("select id from leasing.lead where listing_id = ? and lower(email) = lower(?) limit 1", listingId, email);
            if (dup.isPresent()) return duplicate(out, dup.get().get("id"));
            Map<String, Object> b = new HashMap<>();
            b.put("name", name);
            b.put("email", email);
            if (phone != null) b.put("phone", phone);
            if (message != null) b.put("message", message);
            UUID id = (UUID) leads.enquire(listingId, b, channel, campaignId, false).get("id");
            out.put("id", id);
            out.put("duplicate", false);
            announce(org, campaign, listingId, "lead", name, id, "NORMAL");
        } else {
            if (!j.path("consent").asBoolean(false))
                throw ApiException.badRequest("An application needs \"consent\": true — the applicant agreed to a credit and background check.");
            Optional<Map<String, Object>> dup = db.find("""
                    select id from leasing.application where listing_id = ? and lower(applicant_email) = lower(?) and status not in ('REJECTED','WITHDRAWN') limit 1""", listingId, email);
            if (dup.isPresent()) return duplicate(out, dup.get().get("id"));
            Map<String, Object> b = new HashMap<>();
            String[] parts = name.split("\\s+");
            b.put("firstName", parts[0]);
            b.put("lastName", parts.length > 1 ? name.substring(name.indexOf(' ')).trim() : "-");
            b.put("email", email);
            b.put("consent", true);
            if (phone != null) b.put("phone", phone);
            for (String f : List.of("monthlyIncome", "employer", "monthsEmployed", "currentRent", "occupants", "pets", "moveInDate"))
                if (j.has(f) && !j.get(f).isNull()) b.put(f, j.get(f).isNumber() ? j.get(f).numberValue() : j.get(f).asText());
            UUID id = (UUID) publicService.doApply(listingId, b, channel, campaignId, false).get("id");
            out.put("id", id);
            out.put("duplicate", false);
            announce(org, campaign, listingId, "application", name, id, "HIGH");
        }
        return out;
    }

    private static Map<String, Object> duplicate(Map<String, Object> out, Object id) {
        out.put("id", id);
        out.put("duplicate", true);
        return out;
    }

    private boolean outsideDates(Map<String, Object> c) {
        java.time.LocalDate today = java.time.LocalDate.now();
        Object from = c.get("starts_on"), to = c.get("ends_on");
        return (from != null && today.isBefore(toDate(from))) || (to != null && today.isAfter(toDate(to)));
    }

    private static java.time.LocalDate toDate(Object v) {
        return v instanceof java.sql.Date d ? d.toLocalDate() : java.time.LocalDate.parse(String.valueOf(v));
    }

    /** Tells the company's leasing agents. An application is urgent: someone is ready to rent. */
    private void announce(UUID org, Map<String, Object> campaign, UUID listingId, String kind, String name, UUID id, String priority) {
        try {
            String title = db.find("select title from leasing.listing where id = ?", listingId).map(r -> String.valueOf(r.get("title"))).orElse("a home");
            String what = kind.equals("application") ? "New application" : "New lead";
            UserContextHolder.runAsSystem(org, () -> {
                notifier.sendAlert("ROLE", "LEASING_AGENT", what + " from " + campaign.get("name") + ": " + name,
                        "Came in through the " + String.valueOf(campaign.get("channel")).toLowerCase().replace('_', ' ') + " campaign and asked about " + title + ".",
                        kind.equals("application") ? "/leasing/applications/" + id : "/leasing/leads", priority, "LEASING", "campaign-" + kind + "-" + id);
                return null;
            });
        } catch (RuntimeException e) {
            log.warn("Campaign notice not sent: {}", e.getMessage()); // the lead is saved; a notice that cannot be sent must not lose it
        }
    }
}
