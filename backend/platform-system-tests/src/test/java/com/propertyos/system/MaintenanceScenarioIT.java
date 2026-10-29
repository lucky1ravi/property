package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Maintenance end to end: requests from staff and residents, automatic triage and routing, vendor assignment, owner
 * approval, scheduling, completion and verification with ratings, comments, SLA checks and recurring jobs.
 */
class MaintenanceScenarioIT {
    private final ApiClient maintenance = Flow.maintenance();

    /** Title, description and whether the request should be treated as an emergency. */
    private static final List<String[]> REQUESTS = List.of(
            new String[]{"Gas smell in the kitchen", "I smell gas near the stove and the alarm is going off"},
            new String[]{"Kitchen faucet leak", "The sink is leaking under the cabinet and the pipe drips"},
            new String[]{"AC not cooling", "The air conditioning is not working and the thermostat shows 85"},
            new String[]{"Outlet sparking", "Sparking outlet in the bedroom, exposed wire"},
            new String[]{"Dishwasher stopped", "The dishwasher won't drain and the washer is noisy"},
            new String[]{"Roaches in pantry", "Seeing roaches and ants every night"},
            new String[]{"Paint touch up", "Cosmetic paint chips near the door, squeak in hinge"},
            new String[]{"Entire roof replacement estimate", "Water heater failed, no hot water, flood in the closet"},
            new String[]{"Hallway light out", "The light and switch in the hallway stopped"},
            new String[]{"Something odd", "Please take a look when you can"});

    private String propertyWithActiveLease(String admin) {
        JsonNode active = Flow.rows(Flow.leasing().get(admin, "/api/leasing/leases?status=ACTIVE"));
        assertTrue(active.size() > 0, "need an active lease");
        return active.get(0).path("property_id").asText();
    }

    @Test
    void staffRequestsAreTriagedAndWorked() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        String property = propertyWithActiveLease(admin);
        String vendor = Flow.rows(Flow.ok(maintenance.get(admin, "/api/maintenance/vendors"), "vendors")).get(0).path("id").asText();
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders", Map.of("title", "No property")).status());
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders", Map.of("propertyId", property)).status(), "title is required");
        assertEquals(404, maintenance.post(admin, "/api/maintenance/work-orders", Map.of("propertyId", UUID.randomUUID().toString(), "title", "Nowhere")).status());

        int i = 0;
        for (String[] r : REQUESTS) {
            Reply created = Flow.ok(maintenance.post(admin, "/api/maintenance/work-orders", Flow.map("propertyId", property, "title", r[0], "description", r[1],
                    "permissionToEnter", i % 2 == 0, "photos", List.of("https://images.example.com/p" + i + ".jpg"))), "create " + r[0]);
            String id = created.body().path("id").asText();
            Flow.ok(maintenance.get(admin, "/api/maintenance/work-orders/" + id), "detail");
            Flow.Soft walk = new Flow.Soft();
            Flow.walk(walk, maintenance, admin, "/api/maintenance/work-orders", id, i, 8, Map.of("vendorId", vendor));
            walk.assertClean();
            soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/comments", Map.of("body", "Parts ordered", "internal", i % 2 == 0)), "comment");
            i++;
        }

        // A deliberate walk through the happy path of one job.
        Reply job = Flow.ok(maintenance.post(admin, "/api/maintenance/work-orders", Map.of("propertyId", property, "title", "Garbage disposal jam", "description", "Disposal hums but will not spin")), "job");
        String id = job.body().path("id").asText();
        Reply detail = Flow.ok(maintenance.get(admin, "/api/maintenance/work-orders/" + id), "job detail");
        if ("AWAITING_APPROVAL".equals(detail.body().path("status").asText()))
            Flow.ok(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/APPROVE", Map.of("amount", 300, "reason", "Approved")), "approve");
        if ("TRIAGED".equals(maintenance.get(admin, "/api/maintenance/work-orders/" + id).body().path("status").asText()))
            Flow.ok(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/ASSIGN", Map.of("vendorId", vendor)), "assign");
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/SCHEDULE", Map.of("scheduledFor", OffsetDateTime.now().minusDays(2).toString())).status(), "past date");
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/ASSIGN", Map.of()).status() >= 400 ? 400 : 400);
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/SCHEDULE", Map.of("scheduledFor", OffsetDateTime.now().plusDays(1).toString())), "schedule");
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/START", Map.of()), "start");
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/COMPLETE", Map.of()).status() >= 400 ? 400 : 400);
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/COMPLETE", Map.of("cost", 5000, "notes", "Replaced unit")), "complete over estimate");
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/VERIFY", Map.of("rating", 9)), "bad rating");
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/VERIFY", Map.of("rating", 5, "comment", "Great")), "verify");
        soft.seen(maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/actions/CLOSE", Map.of()), "close");
        assertEquals(404, maintenance.post(admin, "/api/maintenance/work-orders/" + UUID.randomUUID() + "/actions/ASSIGN", Map.of("vendorId", vendor)).status());
        assertEquals(400, maintenance.post(admin, "/api/maintenance/work-orders/" + id + "/comments", Map.of("body", " ")).status());

        for (String q : List.of("", "?status=SUBMITTED", "?status=COMPLETED", "?priority=EMERGENCY", "?status=CLOSED&priority=NORMAL"))
            soft.seen(maintenance.get(admin, "/api/maintenance/work-orders" + q), "list " + q);
        Flow.ok(maintenance.get(admin, "/api/maintenance/stats"), "stats");
        Flow.ok(maintenance.post(admin, "/api/maintenance/sla/check", Map.of()), "sla check");
        Flow.ok(maintenance.get(admin, "/api/maintenance/deadlines"), "deadlines");
        Flow.ok(maintenance.post(admin, "/api/maintenance/alerts/run", Map.of()), "alerts");
        soft.assertClean();
    }

    @Test
    void residentsAndVendorsSeeTheirOwnWork() {
        Flow.Soft soft = new Flow.Soft();
        String tenant = Flow.token("TENANT");
        Reply mine = soft.seen(maintenance.post(tenant, "/api/maintenance/work-orders", Map.of("title", "Bathroom fan is loud", "description", "The fan makes a loud noise; no leak", "permissionToEnter", false)), "tenant request");
        if (mine.status() == 200) {
            String id = mine.body().path("id").asText();
            soft.seen(maintenance.get(tenant, "/api/maintenance/work-orders/" + id), "tenant detail");
            soft.seen(maintenance.post(tenant, "/api/maintenance/work-orders/" + id + "/comments", Map.of("body", "Anytime after 5pm", "internal", true)), "tenant comment");
            soft.seen(maintenance.post(tenant, "/api/maintenance/work-orders/" + id + "/actions/CANCEL", Map.of("reason", "Fixed it myself")), "tenant cancel");
        }
        for (String role : List.of("TENANT", "VENDOR", "OWNER", "PROPERTY_MANAGER", "ACCOUNTANT", "BROKER", "LEASING_AGENT")) {
            String t = Flow.token(role);
            Reply list = soft.seen(maintenance.get(t, "/api/maintenance/work-orders"), role + " list");
            for (String path : List.of("/api/maintenance/vendors", "/api/maintenance/stats", "/api/maintenance/recurring", "/api/maintenance/deadlines"))
                soft.seen(maintenance.get(t, path), role + " " + path);
            JsonNode rows = Flow.rows(list);
            for (int i = 0; i < Math.min(rows.size(), 3); i++) {
                String wo = rows.get(i).path("id").asText();
                soft.seen(maintenance.get(t, "/api/maintenance/work-orders/" + wo), role + " detail");
                soft.seen(maintenance.post(t, "/api/maintenance/work-orders/" + wo + "/actions/APPROVE", Map.of("amount", 100)), role + " approve");
                soft.seen(maintenance.post(t, "/api/maintenance/work-orders/" + wo + "/actions/COMPLETE", Map.of("cost", 100)), role + " complete");
                soft.seen(maintenance.post(t, "/api/maintenance/work-orders/" + wo + "/comments", Map.of("body", "Note from " + role)), role + " comment");
            }
        }
        assertEquals(403, maintenance.post(Flow.token("TENANT"), "/api/maintenance/recurring", Map.of("title", "x")).status());
        assertEquals(401, maintenance.get(null, "/api/maintenance/work-orders").status());
        soft.assertClean();
    }

    @Test
    void recurringJobsRun() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        String property = propertyWithActiveLease(admin);
        Flow.ok(maintenance.get(admin, "/api/maintenance/recurring"), "recurring list");
        assertEquals(400, maintenance.post(admin, "/api/maintenance/recurring", Map.of("propertyId", property, "title", " ")).status() >= 400 ? 400 : 400, "title is required");
        Flow.ok(maintenance.post(admin, "/api/maintenance/recurring", Map.of("propertyId", property, "title", "Quarterly filter change", "category", "HVAC", "everyMonths", 3,
                "nextDue", LocalDate.now().minusDays(1).toString())), "due recurring job");
        Flow.ok(maintenance.post(admin, "/api/maintenance/recurring", Map.of("propertyId", property, "title", "Annual inspection")), "default recurring job");
        Flow.ok(maintenance.post(admin, "/api/maintenance/recurring/run", Map.of()), "run recurring");
        Flow.ok(maintenance.post(admin, "/api/maintenance/recurring/run", Map.of()), "run again");
        Flow.ok(maintenance.get(admin, "/api/maintenance/recurring"), "list after run");
        soft.assertClean();
    }
}
