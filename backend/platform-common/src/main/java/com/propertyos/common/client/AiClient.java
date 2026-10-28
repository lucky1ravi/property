package com.propertyos.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Calls an AI capability on the AI orchestration service. Callers always have a manual or
 * rule-based fallback: if AI is unavailable, the workflow continues without it.
 */
@Component
public class AiClient {
    private static final Logger log = LoggerFactory.getLogger(AiClient.class);
    private final ServiceClient client;

    public AiClient(ServiceClient client) {
        this.client = client;
    }

    public Optional<JsonNode> call(String capability, Map<String, ?> input) {
        try {
            JsonNode r = client.post(ServiceClient.Service.AI, "/internal/ai/" + capability, input);
            return Optional.ofNullable(r);
        } catch (Exception e) {
            log.warn("AI capability {} unavailable, using fallback: {}", capability, e.getMessage());
            return Optional.empty();
        }
    }
}
