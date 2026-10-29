package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.propertyos.ai.service.StudioText.*;

/** Looks at a photo of a repair problem and suggests category, priority, likely cause and a cost range. It is an estimate; a person confirms before any work is approved. */
@Service
public class PhotoTriageTool {
    private static final Logger log = LoggerFactory.getLogger(PhotoTriageTool.class);
    private final ModelRunner runner;
    private final LlmProvider provider;
    private final Guardrails guardrails;
    private final ObjectMapper mapper;
    private final AiAudit audit;

    public PhotoTriageTool(ModelRunner runner, LlmProvider provider, Guardrails guardrails, ObjectMapper mapper, AiAudit audit) {
        this.runner = runner;
        this.provider = provider;
        this.guardrails = guardrails;
        this.mapper = mapper;
        this.audit = audit;
    }

    // Maintenance photo triage
    /**
     * Analyses a maintenance photo with the image model and suggests category, priority and next steps.
     *
     * @param image the photo bytes
     * @param mime  the image type
     * @param note  the resident's description, may be null
     * @throws ApiException (503) when no image model is configured
     */
    public Map<String, Object> triage(byte[] image, String mime, String note) {
        runner.enabled("PHOTO_TRIAGE");
        Llm v = provider.vision().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_OFFLINE", "Photo analysis needs an image model, and none is connected."));
        long t0 = System.currentTimeMillis();
        try {
            String reply = v.vision("You are a maintenance coordinator for a US property management company. You look at one photo of a problem in a rental home. Reply with JSON only: "
                            + "{\"category\": one of PLUMBING, HVAC, ELECTRICAL, APPLIANCE, PEST, GENERAL, \"priority\": one of EMERGENCY, HIGH, NORMAL, LOW, \"safetyRisk\": boolean, "
                            + "\"summary\": one sentence describing what you can see, \"likelyCause\": short, \"suggestedAction\": short, \"estimateLow\": number, \"estimateHigh\": number (typical US repair cost in dollars), "
                            + "\"tenantAdvice\": one safe thing the resident can do right now, \"confidence\": number 0 to 1}. Active water, sparking, fire, gas, exposed wiring or structural danger is EMERGENCY. "
                            + "If the photo does not show a home maintenance problem, use category GENERAL, priority LOW and say so in the summary. Never identify people.",
                    "Triage this photo." + (note == null || note.isBlank() ? "" : " Resident note: " + guardrails.redact(trim(note, 400))), image, mime);
            JsonNode j = mapper.readTree(strip(reply));
            ObjectNode o = mapper.createObjectNode();
            String cat = j.path("category").asText("GENERAL").toUpperCase(), pri = j.path("priority").asText("NORMAL").toUpperCase();
            o.put("category", List.of("PLUMBING", "HVAC", "ELECTRICAL", "APPLIANCE", "PEST", "GENERAL").contains(cat) ? cat : "GENERAL");
            o.put("priority", List.of("EMERGENCY", "HIGH", "NORMAL", "LOW").contains(pri) ? pri : "NORMAL");
            o.put("safetyRisk", j.path("safetyRisk").asBoolean(false));
            o.put("summary", j.path("summary").asText("Could not describe the photo."));
            o.put("likelyCause", j.path("likelyCause").asText(""));
            o.put("suggestedAction", j.path("suggestedAction").asText(""));
            double lo = Math.max(0, j.path("estimateLow").asDouble(0)), hi = Math.max(lo, j.path("estimateHigh").asDouble(lo));
            o.put("estimateLow", Math.round(lo));
            o.put("estimateHigh", Math.round(Math.min(hi, 50000)));
            o.put("tenantAdvice", j.path("tenantAdvice").asText(""));
            double conf = j.path("confidence").asDouble(0.5);
            o.put("confidence", Math.max(0, Math.min(1, conf > 1 ? conf / 100 : conf)));
            o.put("source", "AI");
            o.put("model", v.provider() + ":" + v.model());
            o.put("note", "An estimate from one photo. A person should confirm before any work is approved.");
            audit.record("STUDIO", "photo-triage", "image " + image.length + " bytes", List.of(), v.provider() + ":" + v.model(), 0, 0, false, null, false, System.currentTimeMillis() - t0);
            return mapper.convertValue(o, Map.class);
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Photo triage failed: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "The photo couldn't be analysed. Try a clearer photo or try again.");
        }
    }
}
