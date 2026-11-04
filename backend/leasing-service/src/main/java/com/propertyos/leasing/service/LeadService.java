package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.rls.Db;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.web.Req;
import com.propertyos.common.workflow.StateMachine;
import com.propertyos.common.workflow.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Prospect enquiries (with instant AI reply and scoring) and showings. */
@Service
public class LeadService {
    private com.propertyos.common.security.SuspendedOrgs suspended;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setSuspended(com.propertyos.common.security.SuspendedOrgs suspended) {
        this.suspended = suspended;
    }

    private String suspendedCsv() {
        return suspended == null ? "" : suspended.asCsv();
    }

    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final EventPublisher events;
    private final AiClient ai;
    private final CampaignLookup campaigns;

    public LeadService(Db db, StateMachine sm, WorkflowEngine wf, EventPublisher events, AiClient ai, CampaignLookup campaigns) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.events = events;
        this.ai = ai;
        this.campaigns = campaigns;
    }

    /**
     * Lists leads with the listing they asked about.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> leads() {
        return db.page("""
                select l.*, li.title as listing_title, p.name as property_name, c.name as campaign_name from leasing.lead l
                left join leasing.listing li on li.id = l.listing_id left join leasing.property p on p.id = l.property_id
                left join leasing.campaign c on c.id = l.campaign_id
                order by l.created_at desc""");
    }

    /** Records an enquiry, scores it and drafts a reply. Used by the public site (system context) and brokers. */
    @Transactional
    public Map<String, Object> enquire(UUID listingId, Map<String, Object> b, String source) {
        return enquire(listingId, b, source, null, true);
    }

    /**
     * Records an enquiry for a home of the company that owns {@code campaignId}, or of whichever company a website tracking code ({@code campaign} in
     * the body) points to. {@code announce} is false when the caller sends its own, richer notice (campaign posts do).
     */
    @Transactional
    public Map<String, Object> enquire(UUID listingId, Map<String, Object> b, String source, UUID campaignId, boolean announce) {
        Map<String, Object> li = db.one("Listing", "select * from leasing.listing l where l.id = ? and l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))", listingId, suspendedCsv());
        String name = Req.required(b, "name");
        String email = Req.email(b, "email");
        String msg = Optional.ofNullable(Req.str(b, "message")).orElse("");
        UUID id = UUID.randomUUID();
        int score = 40 + (msg.toLowerCase().matches(".*(tour|showing|apply|move|lease).*") ? 30 : 0) + (Req.str(b, "phone") != null ? 15 : 0);
        String intent = msg.toLowerCase().contains("tour") || msg.toLowerCase().contains("showing") ? "BOOK_SHOWING" : "QUESTION";
        String reply = "Hi " + name.split(" ")[0] + ", thanks for your interest in " + li.get("title") + ". It's available "
                + li.get("available_date") + " at $" + li.get("rent") + " per month. Would you like to tour it this week?";
        if (campaignId == null) campaignId = campaigns.forTrackingCode(Req.str(b, "campaign"), (UUID) li.get("org_id"));
        Optional<JsonNode> out = ai.call("lead-reply", Map.of("listing", li, "message", msg, "name", name));
        if (out.isPresent()) {
            reply = out.get().path("reply").asText(reply);
            score = out.get().path("score").asInt(score);
            intent = out.get().path("intent").asText(intent);
        }
        db.update("""
                insert into leasing.lead (id, org_id, listing_id, property_id, owner_id, name, email, phone, message, source, status, ai_reply, ai_score, ai_intent, campaign_id)
                values (?,?,?,?,?,?,?,?,?,?,'NEW',?,?,?,?)""",
                id, li.get("org_id"), listingId, li.get("property_id"), li.get("owner_id"), name, email, Req.str(b, "phone"), msg, source, reply, score, intent, campaignId);
        db.update("update leasing.listing set enquiries = enquiries + 1 where id = ?", listingId);
        events.publish((UUID) li.get("org_id"), "LeadCreated", "Lead", id, Map.of("listingId", listingId.toString(), "score", score, "name", name));
        // Strong leads already alert the agents through the "hot lead" automation; every other enquiry is announced here so none goes unseen.
        if (announce && score < 70)
            events.publish((UUID) li.get("org_id"), "NotificationRequested", "Notification", UUID.randomUUID(), Map.of("audience", "ROLE", "target", "LEASING_AGENT",
                    "title", "New enquiry: " + name, "body", "Asked about a home on the website: " + (msg.length() > 120 ? msg.substring(0, 117) + "..." : msg),
                    "link", "/leasing/leads", "priority", "NORMAL", "category", "LEASING"));
        return Map.of("id", id, "reply", reply);
    }

    /**
     * Marks a lead as responded.
     *
     * @param leadId the lead
     * @param reply the message sent, or null to keep the AI-drafted reply
     */
    @Transactional
    public Map<String, Object> respond(UUID leadId, String reply) {
        Map<String, Object> lead = db.one("Lead", "select * from leasing.lead where id = ?", leadId);
        db.update("update leasing.lead set status = 'RESPONDED', responded_at = now(), ai_reply = coalesce(?, ai_reply), updated_at = now() where id = ?", reply, leadId);
        Map<String, Object> saved = db.one("Lead", "select * from leasing.lead where id = ?", leadId);
        // The workflow service emails this reply to the prospect: the lead has no account, so the address they gave is the only way to reach them.
        Object listing = db.list("select title from leasing.listing where id = ?", saved.get("listing_id")).stream().findFirst().map(r -> r.get("title")).orElse("");
        Map<String, Object> payload = new HashMap<>();
        payload.put("email", String.valueOf(saved.get("email")));
        payload.put("name", String.valueOf(saved.get("name")));
        payload.put("listing", String.valueOf(listing));
        payload.put("reply", saved.get("ai_reply") == null ? "" : saved.get("ai_reply").toString());
        events.publish("LeadResponded", "Lead", leadId, payload);
        return saved;
    }

    /**
     * Lists showings with listing and property names.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> showings() {
        List<Map<String, Object>> rows = db.page("""
                select s.*, li.title as listing_title, p.name as property_name from leasing.showing s
                join leasing.listing li on li.id = s.listing_id join leasing.property p on p.id = s.property_id order by s.scheduled_at""");
        rows.forEach(r -> r.put("actions", wf.availableActions("SHOWING", (String) r.get("status"))));
        return rows;
    }

    /**
     * Books a showing for a live listing.
     *
     * @param listingId     the listing
     * @param b             scheduledAt (ISO date-time) and visitor details
     * @param publicRequest true when the visitor booked on the public site, so it starts unconfirmed
     * @throws ApiException (404) when the listing is not live
     */
    @Transactional
    public Map<String, Object> book(UUID listingId, Map<String, Object> b, boolean publicRequest) {
        Map<String, Object> li = db.one("Listing", "select * from leasing.listing l where l.id = ? and l.status = 'LIVE' and l.org_id::text <> all (string_to_array(?, ','))", listingId, suspendedCsv());
        OffsetDateTime at = OffsetDateTime.parse(Req.required(b, "scheduledAt"));
        if (at.isBefore(OffsetDateTime.now())) throw ApiException.badRequest("Pick a time in the future.");
        Long clash = db.value(Long.class, "select count(*) from leasing.showing where listing_id = ? and status in ('REQUESTED','CONFIRMED') and scheduled_at = ?", listingId, at);
        if (clash != null && clash > 0) throw ApiException.conflict("That time is already taken. Pick another slot.");
        UUID id = UUID.randomUUID();
        String name = Req.required(b, "name"), email = Req.email(b, "email");
        // A tour asked for through a campaign's tracking link counts for the campaign (only the public form sends the code).
        UUID campaign = publicRequest ? campaigns.forTrackingCode(Req.str(b, "campaign"), (UUID) li.get("org_id")) : null;
        db.update("""
                insert into leasing.showing (id, org_id, listing_id, property_id, owner_id, prospect_name, prospect_email, scheduled_at, type, status, notes, campaign_id)
                values (?,?,?,?,?,?,?,?,?,'REQUESTED',?,?)""",
                id, li.get("org_id"), listingId, li.get("property_id"), li.get("owner_id"), name, email, at,
                Optional.ofNullable(Req.str(b, "type")).orElse("BROKER_LED"), Req.str(b, "notes"), campaign);
        if (publicRequest) ensureLead(li, name, email, Req.str(b, "phone"), "Asked for a tour on " + at.toLocalDate() + ".", "WEBSITE", campaign, 80, "BOOK_SHOWING");
        events.publish((UUID) li.get("org_id"), "ShowingRequested", "Showing", id, Map.of("listingId", listingId.toString(), "at", at.toString()));
        return Map.of("id", id);
    }

    /**
     * Makes sure the person has a lead for this home. A tour request or an application is a prospect too, so it shows on the Leads page and counts for its campaign.
     * An existing lead for the same home and email is kept (it only gains the campaign if it had none), so nobody is listed twice. Staff already hear about the
     * showing or application itself, so this adds no second alert and no AI reply.
     */
    public void ensureLead(Map<String, Object> li, String name, String email, String phone, String message, String source, UUID campaign, int score, String intent) {
        List<Map<String, Object>> found = db.list("select id, campaign_id from leasing.lead where listing_id = ? and lower(email) = lower(?) limit 1", li.get("id"), email);
        if (!found.isEmpty()) {
            if (campaign != null && found.get(0).get("campaign_id") == null)
                db.update("update leasing.lead set campaign_id = ?, updated_at = now() where id = ?", campaign, found.get(0).get("id"));
            return;
        }
        db.update("""
                insert into leasing.lead (id, org_id, listing_id, property_id, owner_id, name, email, phone, message, source, status, ai_score, ai_intent, campaign_id)
                values (?,?,?,?,?,?,?,?,?,?,'NEW',?,?,?)""",
                UUID.randomUUID(), li.get("org_id"), li.get("id"), li.get("property_id"), li.get("owner_id"), name, email, phone, message, source, score, intent, campaign);
    }

    /**
     * Applies a workflow action to a showing.
     */
    @Transactional
    public Map<String, Object> showingAction(UUID id, String action, String reason) {
        sm.apply("Showing", "leasing.showing", id, "SHOWING", action, reason, null, null);
        if ("CONFIRM".equals(action))
            db.update("update leasing.showing set broker_user_id = ?, broker_name = ? where id = ?",
                    UserContextHolder.require().userId(), UserContextHolder.require().name(), id);
        return db.one("Showing", "select * from leasing.showing where id = ?", id);
    }
}
