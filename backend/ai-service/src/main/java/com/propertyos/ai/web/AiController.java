package com.propertyos.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.ai.service.*;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * HTTP API for the assistant, broker insights, AI status and audit, plus internal endpoints used by other services.
 */
@RestController
public class AiController {
    private final ChatService chat;
    private final Insights insights;
    private final Capabilities capabilities;
    private final com.propertyos.ai.service.ImageModeration imageModeration;
    private final AiAudit audit;
    private final LlmProvider provider;

    public AiController(ChatService chat, Insights insights, Capabilities capabilities, AiAudit audit, LlmProvider provider, com.propertyos.ai.service.ImageModeration imageModeration) {
        this.imageModeration = imageModeration;
        this.chat = chat;
        this.insights = insights;
        this.capabilities = capabilities;
        this.audit = audit;
        this.provider = provider;
    }

    /**
     * Sends a message to the assistant. The assistant answers using tools that run with the caller's own permissions.
     */
    @PostMapping("/api/ai/chat") @RequiresPermission("AI_ASSISTANT:VIEW")
    @SuppressWarnings("unchecked")
    public Object chat(@RequestBody Map<String, Object> b) {
        return chat.chat(Upstream.currentBearer(), Req.str(b, "message"), (List<Map<String, String>>) b.get("history"));
    }

    /**
     * Approves or cancels a change the assistant proposed. Nothing is written until it is approved.
     */
    @PostMapping("/api/ai/actions/{id}/confirm") @RequiresPermission("AI_ASSISTANT:VIEW")
    public Object confirm(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return chat.confirm(Upstream.currentBearer(), id, b == null || b.get("approve") == null || Req.bool(b, "approve"));
    }

    /**
     * Lists revenue and savings opportunities found across the portfolio.
     */
    @GetMapping("/api/ai/opportunities") @RequiresPermission("OPPORTUNITY:VIEW")
    public Object opportunities() { return insights.opportunities(Upstream.currentBearer()); }

    /**
     * Returns the broker's prioritised daily queue.
     */
    @GetMapping("/api/ai/broker/queue") @RequiresPermission("DASHBOARD:VIEW")
    public Object queue() { return insights.brokerQueue(Upstream.currentBearer()); }

    /**
     * Returns the active provider, whether it is online, and the guardrails in force.
     */
    @GetMapping("/api/ai/status")
    public Object status() {
        return Map.of("provider", provider.describe(), "online", provider.get().isPresent(),
                "guardrails", List.of("Prompt-injection screening", "Personal data redaction", "Fair-housing screening of generated text", "Rate limiting", "Confirm before any change", "Full audit trail"));
    }

    /**
     * Returns a summary of AI use: interactions, blocked requests, fallbacks, tokens and latency.
     */
    @GetMapping("/api/ai/audit") @RequiresPermission("AUDIT:VIEW")
    public Object audit() { return audit.summary(); }

    /**
     * Service-to-service: checks an uploaded photo and returns a decision with confidence and reasons.
     */
    @PostMapping("/internal/ai/moderate-image")
    public JsonNode moderateImage(@RequestBody JsonNode in) {
        byte[] bytes = java.util.Base64.getDecoder().decode(in.path("imageBase64").asText(""));
        return imageModeration.check(in.path("kind").asText("PROPERTY"), in.path("fileName").asText(""), in.path("mime").asText("image/jpeg"), bytes);
    }

    /**
     * Service-to-service: tells whether an image model is available.
     */
    @GetMapping("/internal/ai/vision-status")
    public Object visionStatus() {
        return Map.of("online", provider.vision().isPresent(), "provider", provider.vision().map(v -> v.provider() + ":" + v.model()).orElse("built-in simulator"));
    }

    /**
     * Service-to-service: runs a named text capability such as listing copy, lead reply, screening, triage or conversation summary.
     */
    @PostMapping("/internal/ai/{capability}")
    public JsonNode capability(@PathVariable String capability, @RequestBody JsonNode input) { return capabilities.run(capability, input); }
}
