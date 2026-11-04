package com.propertyos.workflow.web;

import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import com.propertyos.workflow.webhook.WebhookService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A company's webhooks: send events (application submitted, lease sent, lease signed, payment received, work order created) to other systems as signed JSON.
 * Needs the WEBHOOKS plan feature and the WEBHOOK permissions. A secret appears only in the response that makes or rotates it.
 */
@RestController
@RequestMapping("/api/workflow/webhooks")
public class WebhookController {
    private final WebhookService webhooks;

    public WebhookController(WebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /** The events an endpoint can subscribe to. */
    @GetMapping("/events") @RequiresPermission("WEBHOOK:VIEW")
    public List<Map<String, Object>> events() {
        return webhooks.events();
    }

    /** The company's endpoints with how their recent deliveries went (never the secret). */
    @GetMapping @RequiresPermission("WEBHOOK:VIEW")
    public List<Map<String, Object>> list() {
        return webhooks.list();
    }

    /** Adds an endpoint. Body: name, url (https), events. Returns the secret once. */
    @PostMapping @RequiresPermission("WEBHOOK:CREATE")
    public Map<String, Object> create(@RequestBody Map<String, Object> body) {
        return webhooks.create(body);
    }

    /** The delivery log, newest first (paged). Query: endpointId, status. */
    @GetMapping("/deliveries") @RequiresPermission("WEBHOOK:VIEW")
    public List<Map<String, Object>> deliveries(@RequestParam(required = false) UUID endpointId, @RequestParam(required = false) String status) {
        return webhooks.deliveries(endpointId, status);
    }

    /** One delivery, with the exact JSON that was sent. */
    @GetMapping("/deliveries/{id}") @RequiresPermission("WEBHOOK:VIEW")
    public Map<String, Object> delivery(@PathVariable UUID id) {
        return webhooks.delivery(id);
    }

    /** Queues a failed delivery to be tried again now. */
    @PostMapping("/deliveries/{id}/retry") @RequiresPermission("WEBHOOK:EDIT")
    public Map<String, Object> retry(@PathVariable UUID id) {
        return webhooks.retry(id);
    }

    /** Changes an endpoint's name, url, events or active flag (false switches it off). */
    @PutMapping("/{id}") @RequiresPermission("WEBHOOK:EDIT")
    public Map<String, Object> update(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        return webhooks.update(id, body);
    }

    /** Makes a new signing secret and returns it once. */
    @PostMapping("/{id}/rotate-secret") @RequiresPermission("WEBHOOK:EDIT")
    public Map<String, Object> rotate(@PathVariable UUID id) {
        return webhooks.rotateSecret(id);
    }

    /** Sends a sample event to the endpoint now and reports what it answered. Body: event (optional). Works on a switched-off endpoint. */
    @PostMapping("/{id}/test") @RequiresPermission("WEBHOOK:EDIT")
    public Map<String, Object> test(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> body) {
        return webhooks.sendTest(id, body == null ? null : Req.str(body, "event"));
    }

    /** Deletes an endpoint and its delivery log. */
    @DeleteMapping("/{id}") @RequiresPermission("WEBHOOK:DELETE")
    public Map<String, Object> delete(@PathVariable UUID id) {
        return webhooks.delete(id);
    }
}
