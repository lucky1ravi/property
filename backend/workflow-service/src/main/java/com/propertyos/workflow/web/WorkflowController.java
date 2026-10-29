package com.propertyos.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.workflow.service.AutomationService;
import com.propertyos.workflow.service.DefinitionService;
import com.propertyos.workflow.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * API for workflow definitions, automation rules, chat conversations and notifications.
 */
@RestController
public class WorkflowController {
    private final DefinitionService defs;
    private final AutomationService.Rules rules;
    private final NotificationService.Inbox inbox;
    private final com.propertyos.workflow.service.ChatService chat;

    public WorkflowController(DefinitionService defs, AutomationService.Rules rules, NotificationService.Inbox inbox, com.propertyos.workflow.service.ChatService chat) {
        this.chat = chat;
        this.defs = defs;
        this.rules = rules;
        this.inbox = inbox;
    }

    // ---- Workflow designer
    /**
     * Lists the workflows with their state and transition counts and whether the company has customised each.
     */
    @GetMapping("/api/workflow/workflows") @RequiresPermission("WORKFLOW:VIEW")
    public Object workflows() { return defs.list(); }

    /**
     * Returns a workflow's current definition next to the built-in default.
     */
    @GetMapping("/api/workflow/workflows/{key}") @RequiresPermission("WORKFLOW:VIEW")
    public Object workflow(@PathVariable String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", defs.effective(key));
        m.put("default", defs.defaultOf(key));
        m.put("history", defs.history(key));
        return m;
    }

    /**
     * Saves a customised workflow after validating its states and transitions.
     */
    @PutMapping("/api/workflow/workflows/{key}") @RequiresPermission("WORKFLOW:CONFIGURE")
    public Object saveWorkflow(@PathVariable String key, @RequestBody JsonNode body) { return defs.save(key, body); }

    /**
     * Discards the company's customisation and returns to the built-in workflow.
     */
    @PostMapping("/api/workflow/workflows/{key}/reset") @RequiresPermission("WORKFLOW:CONFIGURE")
    public void reset(@PathVariable String key) { defs.reset(key); }

    // ---- Automation
    /**
     * Lists automation rules.
     */
    @GetMapping("/api/workflow/rules") @RequiresPermission("AUTOMATION:VIEW")
    public Object rules() { return rules.list(); }

    /**
     * Returns one automation rule.
     */
    @GetMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:VIEW")
    public Object rule(@PathVariable UUID id) { return rules.get(id); }

    /**
     * Creates an automation rule (when an event happens, do an action).
     */
    @PostMapping("/api/workflow/rules") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object createRule(@RequestBody Map<String, Object> b) { return rules.save(null, b); }

    /**
     * Changes an automation rule.
     */
    @PutMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object updateRule(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return rules.save(id, b); }

    /**
     * Turns a rule on or off.
     */
    @PostMapping("/api/workflow/rules/{id}/toggle") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object toggle(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return rules.toggle(id, Req.bool(b, "enabled")); }

    /**
     * Deletes a rule.
     */
    @DeleteMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:CONFIGURE")
    public void deleteRule(@PathVariable UUID id) { rules.delete(id); }

    /**
     * Returns automation figures: active rules, runs and failures in the last 30 days, estimated time saved and the latest runs.
     */
    @GetMapping("/api/workflow/automation/summary") @RequiresPermission("AUTOMATION:VIEW")
    public Object summary() { return rules.summary(); }

    /**
     * Lists recent events and the rules they triggered.
     */
    @GetMapping("/api/workflow/automation/events") @RequiresPermission("AUTOMATION:VIEW")
    public Object events() { return rules.events(); }

    // ---- Conversations
    /**
     * Lists the signed-in user's conversations.
     */
    @GetMapping("/api/workflow/chat/conversations") @RequiresPermission("CONVERSATION:VIEW")
    public Object conversations() { return chat.list(); }

    /**
     * Lists people the signed-in user may start a conversation with.
     */
    @GetMapping("/api/workflow/chat/contacts") @RequiresPermission("CONVERSATION:CREATE")
    public Object contacts() { return chat.contacts(); }

    /**
     * Starts a conversation, optionally about a property, lease or work order.
     */
    @PostMapping("/api/workflow/chat/conversations") @RequiresPermission("CONVERSATION:CREATE")
    public Object startConversation(@RequestBody java.util.Map<String, Object> b) { return chat.create(b); }

    /**
     * Returns a conversation with its messages. Only members may read it.
     */
    @GetMapping("/api/workflow/chat/conversations/{id}") @RequiresPermission("CONVERSATION:VIEW")
    public Object conversation(@PathVariable java.util.UUID id) { return chat.get(id); }

    /**
     * Posts a message to a conversation.
     */
    @PostMapping("/api/workflow/chat/conversations/{id}/messages") @RequiresPermission("CONVERSATION:CREATE")
    public Object postMessage(@PathVariable java.util.UUID id, @RequestBody java.util.Map<String, Object> b) { return chat.post(id, b.get("body") == null ? "" : String.valueOf(b.get("body"))); }

    /**
     * Marks a conversation resolved, or reopens it.
     */
    @PostMapping("/api/workflow/chat/conversations/{id}/resolve") @RequiresPermission("CONVERSATION:CREATE")
    public Object resolveConversation(@PathVariable java.util.UUID id, @RequestBody java.util.Map<String, Object> b) { return chat.resolve(id, !Boolean.FALSE.equals(b.get("resolved"))); }

    /**
     * Summarises a conversation with AI.
     */
    @PostMapping("/api/workflow/chat/conversations/{id}/assist") @RequiresPermission("CONVERSATION:VIEW")
    public Object assist(@PathVariable java.util.UUID id) { return chat.assist(id); }

    // ---- Notifications (any signed-in user)
    /**
     * Lists the signed-in user's notifications.
     */
    @GetMapping("/api/workflow/notifications")
    public Object notifications() { return inbox.mine(); }

    /**
     * Returns the signed-in user's notification preferences.
     */
    @GetMapping("/api/workflow/notifications/preferences")
    public Object preferences() { return inbox.preferences(); }

    /**
     * Saves the signed-in user's notification preferences.
     */
    @PutMapping("/api/workflow/notifications/preferences")
    public Object savePreferences(@RequestBody java.util.List<java.util.Map<String, Object>> body) { return inbox.savePreferences(body); }

    /**
     * Marks one notification as read.
     */
    @PostMapping("/api/workflow/notifications/{id}/read")
    public void read(@PathVariable UUID id) { inbox.markRead(id); }

    /**
     * Marks all of the user's notifications as read.
     */
    @PostMapping("/api/workflow/notifications/read-all")
    public void readAll() { inbox.markAllRead(); }

    /**
     * Lists recent notification deliveries (email and in-app) for troubleshooting.
     */
    @GetMapping("/api/workflow/deliveries") @RequiresPermission("AUTOMATION:VIEW")
    public Object deliveries() { return inbox.deliveries(); }

    // ---- Service-to-service
    /**
     * Service-to-service: the effective definition of a workflow, used by the other services' state machines.
     */
    @GetMapping("/internal/workflows/{key}")
    public JsonNode internal(@PathVariable String key) { return defs.effective(key); }
}
