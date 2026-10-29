package com.propertyos.workflow.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.workflow.service.AutomationService;
import com.propertyos.workflow.service.DefinitionService;
import com.propertyos.workflow.service.NotificationService;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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
    @GetMapping("/api/workflow/workflows") @RequiresPermission("WORKFLOW:VIEW")
    public Object workflows() { return defs.list(); }

    @GetMapping("/api/workflow/workflows/{key}") @RequiresPermission("WORKFLOW:VIEW")
    public Object workflow(@PathVariable String key) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("current", defs.effective(key));
        m.put("default", defs.defaultOf(key));
        m.put("history", defs.history(key));
        return m;
    }

    @PutMapping("/api/workflow/workflows/{key}") @RequiresPermission("WORKFLOW:CONFIGURE")
    public Object saveWorkflow(@PathVariable String key, @RequestBody JsonNode body) { return defs.save(key, body); }

    @PostMapping("/api/workflow/workflows/{key}/reset") @RequiresPermission("WORKFLOW:CONFIGURE")
    public void reset(@PathVariable String key) { defs.reset(key); }

    // ---- Automation
    @GetMapping("/api/workflow/rules") @RequiresPermission("AUTOMATION:VIEW")
    public Object rules() { return rules.list(); }

    @GetMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:VIEW")
    public Object rule(@PathVariable UUID id) { return rules.get(id); }

    @PostMapping("/api/workflow/rules") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object createRule(@RequestBody Map<String, Object> b) { return rules.save(null, b); }

    @PutMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object updateRule(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return rules.save(id, b); }

    @PostMapping("/api/workflow/rules/{id}/toggle") @RequiresPermission("AUTOMATION:CONFIGURE")
    public Object toggle(@PathVariable UUID id, @RequestBody Map<String, Object> b) { return rules.toggle(id, Req.bool(b, "enabled")); }

    @DeleteMapping("/api/workflow/rules/{id}") @RequiresPermission("AUTOMATION:CONFIGURE")
    public void deleteRule(@PathVariable UUID id) { rules.delete(id); }

    @GetMapping("/api/workflow/automation/summary") @RequiresPermission("AUTOMATION:VIEW")
    public Object summary() { return rules.summary(); }

    @GetMapping("/api/workflow/automation/events") @RequiresPermission("AUTOMATION:VIEW")
    public Object events() { return rules.events(); }

    // ---- Conversations
    @GetMapping("/api/workflow/chat/conversations") @RequiresPermission("CONVERSATION:VIEW")
    public Object conversations() { return chat.list(); }

    @GetMapping("/api/workflow/chat/contacts") @RequiresPermission("CONVERSATION:CREATE")
    public Object contacts() { return chat.contacts(); }

    @PostMapping("/api/workflow/chat/conversations") @RequiresPermission("CONVERSATION:CREATE")
    public Object startConversation(@RequestBody java.util.Map<String, Object> b) { return chat.create(b); }

    @GetMapping("/api/workflow/chat/conversations/{id}") @RequiresPermission("CONVERSATION:VIEW")
    public Object conversation(@PathVariable java.util.UUID id) { return chat.get(id); }

    @PostMapping("/api/workflow/chat/conversations/{id}/messages") @RequiresPermission("CONVERSATION:CREATE")
    public Object postMessage(@PathVariable java.util.UUID id, @RequestBody java.util.Map<String, Object> b) { return chat.post(id, b.get("body") == null ? "" : String.valueOf(b.get("body"))); }

    @PostMapping("/api/workflow/chat/conversations/{id}/resolve") @RequiresPermission("CONVERSATION:CREATE")
    public Object resolveConversation(@PathVariable java.util.UUID id, @RequestBody java.util.Map<String, Object> b) { return chat.resolve(id, !Boolean.FALSE.equals(b.get("resolved"))); }

    @PostMapping("/api/workflow/chat/conversations/{id}/assist") @RequiresPermission("CONVERSATION:VIEW")
    public Object assist(@PathVariable java.util.UUID id) { return chat.assist(id); }

    // ---- Notifications (any signed-in user)
    @GetMapping("/api/workflow/notifications")
    public Object notifications() { return inbox.mine(); }

    @GetMapping("/api/workflow/notifications/preferences")
    public Object preferences() { return inbox.preferences(); }

    @PutMapping("/api/workflow/notifications/preferences")
    public Object savePreferences(@RequestBody java.util.List<java.util.Map<String, Object>> body) { return inbox.savePreferences(body); }

    @PostMapping("/api/workflow/notifications/{id}/read")
    public void read(@PathVariable UUID id) { inbox.markRead(id); }

    @PostMapping("/api/workflow/notifications/read-all")
    public void readAll() { inbox.markAllRead(); }

    @GetMapping("/api/workflow/deliveries") @RequiresPermission("AUTOMATION:VIEW")
    public Object deliveries() { return inbox.deliveries(); }

    // ---- Service-to-service
    @GetMapping("/internal/workflows/{key}")
    public JsonNode internal(@PathVariable String key) { return defs.effective(key); }
}
