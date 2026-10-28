package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.EventPublisher;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import com.propertyos.common.web.ApiException;
import com.propertyos.common.workflow.StateMachine;
import com.propertyos.common.workflow.WorkflowEngine;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/** Applications: simulated screening, AI summary and recommendation, decisions. */
@Service
public class ApplicationService {
    private static final String WF = "APPLICATION";
    private final Db db;
    private final StateMachine sm;
    private final WorkflowEngine wf;
    private final ConfigClient config;
    private final EventPublisher events;
    private final Notifier notifier;
    private final AiClient ai;

    public ApplicationService(Db db, StateMachine sm, WorkflowEngine wf, ConfigClient config, EventPublisher events, Notifier notifier, AiClient ai) {
        this.db = db;
        this.sm = sm;
        this.wf = wf;
        this.config = config;
        this.events = events;
        this.notifier = notifier;
        this.ai = ai;
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> list(String status) {
        String sql = """
                select a.id, a.applicant_name, a.applicant_email, a.unit_label, a.status, a.monthly_income, a.credit_score, a.background_clear,
                       a.submitted_at, a.move_in_date, a.screening->'recommendation' as recommendation, l.rent
                from leasing.application a join leasing.listing l on l.id = a.listing_id""";
        return status == null ? db.list(sql + " order by a.submitted_at desc") : db.list(sql + " where a.status = ? order by a.submitted_at desc", status);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> get(UUID id) {
        Map<String, Object> a = new LinkedHashMap<>(db.one("Application", """
                select a.*, l.rent as listing_rent, l.deposit as listing_deposit from leasing.application a join leasing.listing l on l.id = a.listing_id where a.id = ?""", id));
        a.put("actions", wf.availableActions(WF, (String) a.get("status")));
        a.put("lease", db.find("select id, status from leasing.lease where application_id = ?", id).orElse(null));
        return a;
    }

    /** Runs the (simulated) credit and background check, then asks AI for a summary. */
    @Transactional
    public Map<String, Object> screen(UUID id) {
        Map<String, Object> a = db.one("Application", """
                select a.*, l.rent from leasing.application a join leasing.listing l on l.id = a.listing_id where a.id = ?""", id);
        String status = (String) a.get("status");
        if (!List.of("SUBMITTED", "DOCS_PENDING").contains(status))
            throw ApiException.conflict("Screening has already been completed for this application.");
        BigDecimal rent = (BigDecimal) a.get("rent");
        BigDecimal income = (BigDecimal) a.get("monthly_income");
        int credit = a.get("credit_score") != null ? (Integer) a.get("credit_score")
                : 560 + Math.floorMod((a.get("applicant_email").toString()).hashCode(), 240);
        boolean clear = Math.floorMod(a.get("applicant_email").toString().hashCode() / 7, 11) != 0;
        BigDecimal ratio = income == null || rent.signum() == 0 ? BigDecimal.ZERO : income.divide(rent, 2, RoundingMode.HALF_UP);
        BigDecimal minRatio = config.number("screening.incomeMultiple", "3");
        int minCredit = 600 + config.number("screening.minScore", "60").intValue() / 3;
        List<String> reasons = new ArrayList<>();
        int score = 100;
        if (ratio.compareTo(minRatio) < 0) {
            reasons.add("Income is " + ratio + "x rent; policy requires " + minRatio + "x.");
            score -= 30;
        }
        if (credit < minCredit) {
            reasons.add("Credit score " + credit + " is below the " + minCredit + " minimum.");
            score -= 30;
        }
        if (!clear) {
            reasons.add("Background check found an item that needs manual review.");
            score -= 30;
        }
        String rec = reasons.isEmpty() ? "APPROVE" : score >= 40 ? "REVIEW" : "DECLINE";
        Map<String, Object> screening = new LinkedHashMap<>();
        screening.put("provider", "Simulated bureau (demo)");
        screening.put("incomeToRent", ratio);
        screening.put("creditScore", credit);
        screening.put("backgroundClear", clear);
        screening.put("score", Math.max(score, 0));
        screening.put("recommendation", rec);
        screening.put("reasons", reasons);
        screening.put("summary", reasons.isEmpty()
                ? "Meets income, credit and background criteria. Income is " + ratio + "x rent with a " + credit + " credit score."
                : String.join(" ", reasons));
        ai.call("screening", Map.of("application", a, "screening", screening))
                .map(n -> n.path("summary").asText(null)).ifPresent(s -> screening.put("summary", s));
        screening.put("guardrail", "AI recommends; a person makes the decision. Decisions are checked against fair-housing rules.");
        db.update("update leasing.application set screening = ?::jsonb, credit_score = ?, background_clear = ? where id = ?",
                db.json(screening), credit, clear, id);
        sm.applyAsSystem("Application", "leasing.application", id, WF, "SCREEN_COMPLETE", "Screening completed", Map.of("recommendation", rec));
        return get(id);
    }

    @Transactional
    public Map<String, Object> act(UUID id, String action, String reason) {
        Map<String, Object> a = db.one("Application", "select * from leasing.application where id = ?", id);
        StateMachine.Outcome o = sm.apply("Application", "leasing.application", id, WF, action, reason, null, null);
        if (List.of("APPROVED", "REJECTED").contains(o.to())) {
            db.update("update leasing.application set decision_reason = ?, decided_by = ?, decided_at = now() where id = ?", reason,
                    com.propertyos.common.security.UserContextHolder.require().name(), id);
            events.publish("ApplicationDecided", "Application", id, Map.of("decision", o.to(), "applicantEmail", a.get("applicant_email").toString(),
                    "applicantName", a.get("applicant_name").toString()));
            if ("REJECTED".equals(o.to())) {
                // Adverse action notice is required in the US when a decision relies on a consumer report.
                db.update("update leasing.application set decision_reason = ? where id = ?",
                        (reason == null ? "" : reason + " ") + "An adverse action notice has been sent as required by the Fair Credit Reporting Act.", id);
            }
        }
        if ("OWNER_REVIEW".equals(o.to()))
            notifier.toParty((UUID) a.get("owner_id"), "Application ready for your review", a.get("applicant_name") + " applied for " + a.get("unit_label") + ".", "/leasing/applications/" + id);
        return get(id);
    }
}
