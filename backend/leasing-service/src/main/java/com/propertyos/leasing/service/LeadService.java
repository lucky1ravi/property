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
    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final EventPublisher events;
    private final AiClient ai;

    public LeadService(Db db, StateMachine sm, WorkflowEngine wf, EventPublisher events, AiClient ai) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.events = events;
        this.ai = ai;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> leads() {
        return db.list("""
                select l.*, li.title as listing_title, p.name as property_name from leasing.lead l
                left join leasing.listing li on li.id = l.listing_id left join leasing.property p on p.id = l.property_id
                order by l.created_at desc""");
    }

    /** Records an enquiry, scores it and drafts a reply. Used by the public site (system context) and brokers. */
    @Transactional
    public Map<String, Object> enquire(UUID listingId, Map<String, Object> b, String source) {
        Map<String, Object> li = db.one("Listing", "select * from leasing.listing where id = ? and status = 'LIVE'", listingId);
        String name = Req.required(b, "name");
        String email = Req.email(b, "email");
        String msg = Optional.ofNullable(Req.str(b, "message")).orElse("");
        UUID id = UUID.randomUUID();
        int score = 40 + (msg.toLowerCase().matches(".*(tour|showing|apply|move|lease).*") ? 30 : 0) + (Req.str(b, "phone") != null ? 15 : 0);
        String intent = msg.toLowerCase().contains("tour") || msg.toLowerCase().contains("showing") ? "BOOK_SHOWING" : "QUESTION";
        String reply = "Hi " + name.split(" ")[0] + ", thanks for your interest in " + li.get("title") + ". It's available "
                + li.get("available_date") + " at $" + li.get("rent") + " per month. Would you like to tour it this week?";
        Optional<JsonNode> out = ai.call("lead-reply", Map.of("listing", li, "message", msg, "name", name));
        if (out.isPresent()) {
            reply = out.get().path("reply").asText(reply);
            score = out.get().path("score").asInt(score);
            intent = out.get().path("intent").asText(intent);
        }
        db.update("""
                insert into leasing.lead (id, org_id, listing_id, property_id, owner_id, name, email, phone, message, source, status, ai_reply, ai_score, ai_intent)
                values (?,?,?,?,?,?,?,?,?,?,'NEW',?,?,?)""",
                id, li.get("org_id"), listingId, li.get("property_id"), li.get("owner_id"), name, email, Req.str(b, "phone"), msg, source, reply, score, intent);
        db.update("update leasing.listing set enquiries = enquiries + 1 where id = ?", listingId);
        events.publish((UUID) li.get("org_id"), "LeadCreated", "Lead", id, Map.of("listingId", listingId.toString(), "score", score, "name", name));
        return Map.of("id", id, "reply", reply);
    }

    @Transactional
    public Map<String, Object> respond(UUID leadId, String reply) {
        Map<String, Object> lead = db.one("Lead", "select * from leasing.lead where id = ?", leadId);
        db.update("update leasing.lead set status = 'RESPONDED', responded_at = now(), ai_reply = coalesce(?, ai_reply), updated_at = now() where id = ?", reply, leadId);
        events.publish("LeadResponded", "Lead", leadId, Map.of("email", lead.get("email").toString()));
        return db.one("Lead", "select * from leasing.lead where id = ?", leadId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> showings() {
        List<Map<String, Object>> rows = db.list("""
                select s.*, li.title as listing_title, p.name as property_name from leasing.showing s
                join leasing.listing li on li.id = s.listing_id join leasing.property p on p.id = s.property_id order by s.scheduled_at""");
        rows.forEach(r -> r.put("actions", wf.availableActions("SHOWING", (String) r.get("status"))));
        return rows;
    }

    @Transactional
    public Map<String, Object> book(UUID listingId, Map<String, Object> b, boolean publicRequest) {
        Map<String, Object> li = db.one("Listing", "select * from leasing.listing where id = ? and status = 'LIVE'", listingId);
        OffsetDateTime at = OffsetDateTime.parse(Req.required(b, "scheduledAt"));
        if (at.isBefore(OffsetDateTime.now())) throw ApiException.badRequest("Pick a time in the future.");
        Long clash = db.value(Long.class, "select count(*) from leasing.showing where listing_id = ? and status in ('REQUESTED','CONFIRMED') and scheduled_at = ?", listingId, at);
        if (clash != null && clash > 0) throw ApiException.conflict("That time is already taken. Pick another slot.");
        UUID id = UUID.randomUUID();
        db.update("""
                insert into leasing.showing (id, org_id, listing_id, property_id, owner_id, prospect_name, prospect_email, scheduled_at, type, status, notes)
                values (?,?,?,?,?,?,?,?,?,'REQUESTED',?)""",
                id, li.get("org_id"), listingId, li.get("property_id"), li.get("owner_id"), Req.required(b, "name"), Req.email(b, "email"), at,
                Optional.ofNullable(Req.str(b, "type")).orElse("BROKER_LED"), Req.str(b, "notes"));
        events.publish((UUID) li.get("org_id"), "ShowingRequested", "Showing", id, Map.of("listingId", listingId.toString(), "at", at.toString()));
        return Map.of("id", id);
    }

    @Transactional
    public Map<String, Object> showingAction(UUID id, String action, String reason) {
        sm.apply("Showing", "leasing.showing", id, "SHOWING", action, reason, null, null);
        if ("CONFIRM".equals(action))
            db.update("update leasing.showing set broker_user_id = ?, broker_name = ? where id = ?",
                    UserContextHolder.require().userId(), UserContextHolder.require().name(), id);
        return db.one("Showing", "select * from leasing.showing where id = ?", id);
    }
}
