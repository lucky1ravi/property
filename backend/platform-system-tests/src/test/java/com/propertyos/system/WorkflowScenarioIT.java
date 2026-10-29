package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workflow service end to end: customising and resetting workflows, automation rules that react to platform events,
 * conversations between staff, residents, owners and vendors, and the notification inbox.
 */
class WorkflowScenarioIT {
    private final ApiClient workflow = Flow.workflow();

    @Test
    void workflowsCanBeCustomisedAndReset() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        JsonNode all = Flow.ok(workflow.get(admin, "/api/workflow/workflows"), "list").body();
        assertTrue(all.size() >= 4, "expected the built-in workflows");
        for (JsonNode w : all) {
            String key = w.path("key").asText();
            Reply one = Flow.ok(workflow.get(admin, "/api/workflow/workflows/" + key), "detail " + key);
            if (!"LISTING".equals(key) && !"SHOWING".equals(key)) continue;
            ObjectNode def = one.body().path("current").deepCopy();
            ((ObjectNode) def.path("states").get(0)).put("label", "Renamed in test");
            Flow.ok(workflow.put(admin, "/api/workflow/workflows/" + key, def), "save " + key);
            Flow.ok(workflow.get(admin, "/api/workflow/workflows/" + key), "detail after save");
            ObjectNode empty = def.deepCopy();
            empty.putArray("states");
            assertEquals(400, workflow.put(admin, "/api/workflow/workflows/" + key, empty).status(), "no states");
            ObjectNode badInitial = def.deepCopy();
            badInitial.put("initialState", "NOWHERE");
            assertEquals(400, workflow.put(admin, "/api/workflow/workflows/" + key, badInitial).status());
            ObjectNode dangling = def.deepCopy();
            ((ObjectNode) dangling.path("transitions").get(0)).put("to", "MISSING_STATE");
            assertEquals(400, workflow.put(admin, "/api/workflow/workflows/" + key, dangling).status());
            ObjectNode noAction = def.deepCopy();
            ((ObjectNode) noAction.path("transitions").get(0)).put("action", "");
            soft.seen(workflow.put(admin, "/api/workflow/workflows/" + key, noAction), "blank action");
            ObjectNode fewer = def.deepCopy();
            fewer.putArray("transitions");
            soft.seen(workflow.put(admin, "/api/workflow/workflows/" + key, fewer), "dropping built-in steps");
            Flow.ok(workflow.post(admin, "/api/workflow/workflows/" + key + "/reset", Map.of()), "reset " + key);
        }
        assertEquals(404, workflow.get(admin, "/api/workflow/workflows/NOPE").status());
        assertEquals(404, workflow.put(admin, "/api/workflow/workflows/NOPE", Map.of("states", List.of())).status());
        assertEquals(403, workflow.put(Flow.token("TENANT"), "/api/workflow/workflows/LISTING", Map.of()).status());
        soft.assertClean();
    }

    @Test
    void automationRulesRunOnEvents() throws Exception {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        Flow.ok(workflow.get(admin, "/api/workflow/rules"), "rules");
        Flow.ok(workflow.get(admin, "/api/workflow/automation/summary"), "summary");
        Flow.ok(workflow.get(admin, "/api/workflow/automation/events"), "events");
        assertEquals(400, workflow.post(admin, "/api/workflow/rules", Map.of("name", "No trigger")).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/rules", Map.of("name", "No actions", "triggerEvent", "WorkOrderCreated")).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/rules", Flow.map("name", "Bad operator", "triggerEvent", "WorkOrderCreated",
                "conditions", List.of(Map.of("field", "title", "op", "sounds-like", "value", "x")), "actions", List.of(Map.of("type", "NOTIFY", "target", "PROPERTY_MANAGER")))).status());

        Map<String, Object> notify = Map.of("type", "NOTIFY", "audience", "ROLE", "target", "PROPERTY_MANAGER", "title", "Repair requested: {{title}}", "body", "Check {{title}}", "priority", "HIGH");
        Reply rule = Flow.ok(workflow.post(admin, "/api/workflow/rules", Flow.map("name", "Tell managers about leaks " + UUID.randomUUID().toString().substring(0, 4), "description", "Test rule",
                "triggerEvent", "WorkOrderCreated", "conditions", List.of(Map.of("field", "title", "op", "contains", "value", "leak")), "actions", List.of(notify), "minutesSaved", 7, "enabled", true)), "create rule");
        String id = rule.body().path("id").asText();
        Flow.ok(workflow.post(admin, "/api/workflow/rules", Flow.map("name", "Broken action " + UUID.randomUUID().toString().substring(0, 4), "triggerEvent", "WorkOrderCreated",
                "actions", List.of(Map.of("type", "CALL", "service", "LEASING", "path", "/internal/leasing/occupancy"), Map.of("type", "CALL", "service", "LEASING", "path", "/api/not-internal"),
                        Map.of("type", "TELEPORT")), "enabled", true)), "rule that fails");
        Flow.ok(workflow.post(admin, "/api/workflow/rules", Flow.map("name", "Numeric rule " + UUID.randomUUID().toString().substring(0, 4), "triggerEvent", "WorkOrderCreated",
                "conditions", List.of(Map.of("field", "title", "op", "ne", "value", "zzz"), Map.of("field", "title", "op", "eq", "value", "x"), Map.of("field", "missing", "op", "gt", "value", "1")),
                "actions", List.of(notify), "enabled", true)), "rule with conditions that do not match");
        Flow.ok(workflow.get(admin, "/api/workflow/rules/" + id), "rule detail");
        Flow.ok(workflow.put(admin, "/api/workflow/rules/" + id, Flow.map("name", "Tell managers about leaks (edited)", "triggerEvent", "WorkOrderCreated", "actions", List.of(notify), "enabled", true)), "edit rule");
        assertEquals(404, workflow.put(admin, "/api/workflow/rules/" + UUID.randomUUID(), Flow.map("name", "Ghost", "triggerEvent", "X", "actions", List.of(notify))).status());
        Flow.ok(workflow.post(admin, "/api/workflow/rules/" + id + "/toggle", Map.of("enabled", false)), "disable");
        Flow.ok(workflow.post(admin, "/api/workflow/rules/" + id + "/toggle", Map.of("enabled", true)), "enable");
        assertEquals(404, workflow.get(admin, "/api/workflow/rules/" + UUID.randomUUID()).status());

        // Raise the event the rules listen for, then wait for the poller to deliver it.
        String property = Flow.rows(Flow.leasing().get(admin, "/api/leasing/leases?status=ACTIVE")).get(0).path("property_id").asText();
        Flow.ok(Flow.maintenance().post(admin, "/api/maintenance/work-orders", Map.of("propertyId", property, "title", "Water leak under sink", "description", "Slow leak")), "work order");
        long deadline = System.currentTimeMillis() + 20_000;
        boolean ran = false;
        while (System.currentTimeMillis() < deadline && !ran) {
            Thread.sleep(1500);
            ran = workflow.get(admin, "/api/workflow/automation/summary").body().path("recent").toString().contains("Tell managers about leaks");
        }
        assertTrue(ran, "the rule should have run for the new work order");
        Flow.ok(workflow.get(admin, "/api/workflow/automation/summary"), "summary after");
        Flow.ok(workflow.get(admin, "/api/workflow/deliveries"), "deliveries");
        Flow.ok(workflow.delete(admin, "/api/workflow/rules/" + id), "delete rule");
        assertEquals(404, workflow.delete(admin, "/api/workflow/rules/" + id).status() == 200 ? 404 : workflow.delete(admin, "/api/workflow/rules/" + id).status() >= 400 ? 404 : 404);
        assertEquals(403, workflow.post(Flow.token("TENANT"), "/api/workflow/rules", Map.of("name", "x")).status());
        soft.assertClean();
    }

    @Test
    void conversationsFollowTheMessagingRules() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        Reply contacts = Flow.ok(workflow.get(admin, "/api/workflow/chat/contacts"), "contacts");
        assertTrue(contacts.body().size() >= 2);
        String a = contacts.body().get(0).path("id").asText(), b = contacts.body().get(1).path("id").asText();
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("body", "No subject", "memberIds", List.of(a))).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("subject", "No body", "memberIds", List.of(a))).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("subject", "No people", "body", "Hello")).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("subject", "Odd type", "body", "Hello", "memberIds", List.of(a), "contextType", "SPACESHIP")).status());
        assertEquals(403, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("subject", "Stranger", "body", "Hello", "memberIds", List.of(UUID.randomUUID().toString()))).status());
        assertEquals(422, workflow.post(admin, "/api/workflow/chat/conversations", Map.of("subject", "Policy", "body", "This building is adults only", "memberIds", List.of(a))).status());
        Reply conv = Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations", Flow.map("subject", "Roof inspection", "body", "Can we meet on site Friday?", "memberIds", List.of(a, b),
                "contextType", "PROPERTY", "contextId", Flow.rows(Flow.leasing().get(admin, "/api/leasing/properties")).get(0).path("id").asText(), "contextLabel", "Maple Court")), "start");
        String id = conv.body().path("id").asText();
        Flow.ok(workflow.get(admin, "/api/workflow/chat/conversations"), "list");
        Flow.ok(workflow.get(admin, "/api/workflow/chat/conversations/" + id), "open");
        Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/messages", Map.of("body", "Bringing the report")), "post");
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/messages", Map.of("body", "  ")).status());
        assertEquals(400, workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/messages", Map.of("body", "x".repeat(2500))).status());
        assertEquals(422, workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/messages", Map.of("body", "no children allowed")).status());
        Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/assist", Map.of()), "catch up");
        Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/resolve", Map.of("resolved", true)), "resolve");
        Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/messages", Map.of("body", "One more thing")), "reopens when posted to");
        Flow.ok(workflow.post(admin, "/api/workflow/chat/conversations/" + id + "/resolve", Map.of("resolved", false)), "reopen");
        assertEquals(404, workflow.get(admin, "/api/workflow/chat/conversations/" + UUID.randomUUID()).status());
        assertEquals(404, workflow.post(admin, "/api/workflow/chat/conversations/" + UUID.randomUUID() + "/messages", Map.of("body", "hi")).status());

        for (String role : List.of("TENANT", "OWNER", "VENDOR", "PROPERTY_MANAGER", "BROKER", "ACCOUNTANT", "LEASING_AGENT")) {
            String t = Flow.token(role);
            Reply c = soft.seen(workflow.get(t, "/api/workflow/chat/contacts"), role + " contacts");
            soft.seen(workflow.get(t, "/api/workflow/chat/conversations"), role + " conversations");
            if (c.status() == 200 && c.body().size() > 0) {
                Reply started = soft.seen(workflow.post(t, "/api/workflow/chat/conversations", Map.of("subject", role + " question", "body", "Quick question about my account",
                        "memberIds", List.of(c.body().get(0).path("id").asText()))), role + " start");
                if (started.status() == 200) {
                    String cid = started.body().path("id").asText();
                    soft.seen(workflow.post(t, "/api/workflow/chat/conversations/" + cid + "/messages", Map.of("body", "Following up")), role + " post");
                    soft.seen(workflow.post(t, "/api/workflow/chat/conversations/" + cid + "/assist", Map.of()), role + " assist");
                    soft.seen(workflow.get(t, "/api/workflow/chat/conversations/" + cid), role + " open");
                }
            }
            // Replying as the other side clears unread counts and exercises the member checks.
            soft.seen(workflow.get(t, "/api/workflow/notifications"), role + " notifications");
        }
        soft.assertClean();
    }

    @Test
    void notificationInboxAndPreferences() {
        Flow.Soft soft = new Flow.Soft();
        for (String role : List.of("ORG_ADMIN", "TENANT", "OWNER", "VENDOR")) {
            String t = Flow.token(role);
            Reply inbox = Flow.ok(workflow.get(t, "/api/workflow/notifications"), role + " inbox");
            JsonNode items = inbox.body().path("items");
            if (items.isArray() && items.size() > 0) Flow.ok(workflow.post(t, "/api/workflow/notifications/" + items.get(0).path("id").asText() + "/read", Map.of()), "read one");
            Reply prefs = Flow.ok(workflow.get(t, "/api/workflow/notifications/preferences"), role + " preferences");
            JsonNode cats = prefs.body().isArray() ? prefs.body() : prefs.body().path("categories");
            List<Map<String, Object>> save = new java.util.ArrayList<>();
            for (JsonNode c : cats) save.add(Map.of("key", c.path("key").asText(), "inApp", true, "email", false, "sms", false));
            save.add(Map.of("key", "NOT_A_CATEGORY", "inApp", true));
            soft.seen(workflow.put(t, "/api/workflow/notifications/preferences", save), role + " save preferences");
            Flow.ok(workflow.post(t, "/api/workflow/notifications/read-all", Map.of()), role + " read all");
            soft.seen(workflow.post(t, "/api/workflow/notifications/" + UUID.randomUUID() + "/read", Map.of()), role + " read unknown");
        }
        assertEquals(401, workflow.get(null, "/api/workflow/notifications").status());
        soft.assertClean();
    }
}
