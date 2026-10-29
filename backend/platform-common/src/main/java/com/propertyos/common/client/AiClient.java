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

    /**
     * Calls an AI capability in the AI service.
     *
     * @param capability the capability name
     * @param input      the request data
     * @return the response, or empty when the AI service is unavailable so callers can fall back to rules
     */
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
