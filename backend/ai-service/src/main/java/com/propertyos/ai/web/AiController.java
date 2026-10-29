package com.propertyos.ai.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.ai.service.*;
import com.propertyos.common.security.RequiresPermission;
import com.propertyos.common.web.Req;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

    @PostMapping("/api/ai/chat") @RequiresPermission("AI_ASSISTANT:VIEW")
    @SuppressWarnings("unchecked")
    public Object chat(@RequestBody Map<String, Object> b) {
        return chat.chat(Upstream.currentBearer(), Req.str(b, "message"), (List<Map<String, String>>) b.get("history"));
    }

    @PostMapping("/api/ai/actions/{id}/confirm") @RequiresPermission("AI_ASSISTANT:VIEW")
    public Object confirm(@PathVariable UUID id, @RequestBody(required = false) Map<String, Object> b) {
        return chat.confirm(Upstream.currentBearer(), id, b == null || b.get("approve") == null || Req.bool(b, "approve"));
    }

    @GetMapping("/api/ai/opportunities") @RequiresPermission("OPPORTUNITY:VIEW")
    public Object opportunities() { return insights.opportunities(Upstream.currentBearer()); }

    @GetMapping("/api/ai/broker/queue") @RequiresPermission("DASHBOARD:VIEW")
    public Object queue() { return insights.brokerQueue(Upstream.currentBearer()); }

    @GetMapping("/api/ai/status")
    public Object status() {
        return Map.of("provider", provider.describe(), "online", provider.get().isPresent(),
                "guardrails", List.of("Prompt-injection screening", "Personal data redaction", "Fair-housing screening of generated text", "Rate limiting", "Confirm before any change", "Full audit trail"));
    }

    @GetMapping("/api/ai/audit") @RequiresPermission("AUDIT:VIEW")
    public Object audit() { return audit.summary(); }

    @PostMapping("/internal/ai/moderate-image")
    public JsonNode moderateImage(@RequestBody JsonNode in) {
        byte[] bytes = java.util.Base64.getDecoder().decode(in.path("imageBase64").asText(""));
        return imageModeration.check(in.path("kind").asText("PROPERTY"), in.path("fileName").asText(""), in.path("mime").asText("image/jpeg"), bytes);
    }

    @GetMapping("/internal/ai/vision-status")
    public Object visionStatus() {
        return Map.of("online", provider.vision().isPresent(), "provider", provider.vision().map(v -> v.provider() + ":" + v.model()).orElse("built-in simulator"));
    }

    @PostMapping("/internal/ai/{capability}")
    public JsonNode capability(@PathVariable String capability, @RequestBody JsonNode input) { return capabilities.run(capability, input); }
}
