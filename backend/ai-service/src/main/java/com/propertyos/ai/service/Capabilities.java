package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Single-shot AI capabilities used by other services (listing copy, lead replies, screening summaries, maintenance triage).
 * Each returns validated JSON. When the model is unavailable, disabled by configuration, or a guardrail trips,
 * the caller receives an error and uses its rule-based fallback, so no workflow ever depends on the model.
 */
@Service
public class Capabilities {
    private final LlmProvider provider;
    private final Guardrails guardrails;
    private final ObjectMapper mapper;
    private final ConfigClient config;
    private final AiAudit audit;

    public Capabilities(LlmProvider provider, Guardrails guardrails, ObjectMapper mapper, ConfigClient config, AiAudit audit) {
        this.provider = provider;
        this.guardrails = guardrails;
        this.mapper = mapper;
        this.config = config;
        this.audit = audit;
    }

    /**
     * Maps each capability name to the configuration key that switches it on or off.
     */
    private static final Map<String, String> CONFIG_KEY = Map.of("listing", "LISTING_WRITER", "lead-reply", "LEASING_AGENT", "screening", "SCREENING", "triage", "MAINTENANCE_TRIAGE", "thread", "CONVERSATION_ASSIST");

    /**
     * Runs a named text capability: checks it is switched on, applies guardrails, calls the model and falls back to rules when none is available.
     *
     * @param capability listing, lead-reply, screening, triage or thread
     * @param input      the capability's request data
     * @throws ApiException (404) for an unknown capability, (503) when turned off
     */
    public JsonNode run(String capability, JsonNode input) {
        String key = CONFIG_KEY.get(capability);
        if (key == null) throw ApiException.notFound("AI capability");
        if (!config.flag("ai." + key + ".enabled", true)) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "This AI feature is turned off for your organization.");
        Llm llm = provider.get().orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_OFFLINE", "No AI model is configured."));
        long t0 = System.currentTimeMillis();
        String system = systemFor(capability);
        String user = guardrails.redact(trim(input.toString(), 6000));
        try {
            Llm.Reply r = llm.chat(system, List.of(Llm.Msg.user(user)), List.of(), true);
            JsonNode out = mapper.readTree(stripFence(r.text()));
            validate(capability, out);
            audit.record("CAPABILITY", capability, user, List.of(), llm.provider() + ":" + llm.model(), r.inputTokens(), r.outputTokens(), false, null, false, System.currentTimeMillis() - t0);
            return out;
        } catch (ApiException e) {
            audit.record("CAPABILITY", capability, user, List.of(), llm.provider(), 0, 0, true, e.getMessage(), true, System.currentTimeMillis() - t0);
            throw e;
        } catch (Exception e) {
            audit.record("CAPABILITY", capability, user, List.of(), llm.provider(), 0, 0, false, e.getMessage(), true, System.currentTimeMillis() - t0);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_FAILED", "The AI model didn't return a usable answer.");
        }
    }

    private String systemFor(String c) {
        String base = "You are an assistant inside a US property management platform. Reply with JSON only. Never include personal data beyond what is provided. ";
        return switch (c) {
            case "listing" -> base + "Write rental listing copy. Return {\"title\": string under 90 chars, \"description\": 80-140 words}. Describe the home and its features only. "
                    + "Never mention or imply who should or should not live there (fair housing law). Do not invent amenities that are not in the input.";
            case "lead-reply" -> base + "Draft a warm, short reply (under 70 words) to a rental enquiry and score it. Return {\"reply\": string, \"score\": integer 0-100 for likelihood to lease, "
                    + "\"intent\": one of BOOK_SHOWING, QUESTION, APPLY, PRICE}. Only state facts present in the input. Offer to schedule a tour.";
            case "screening" -> base + "Summarise an applicant screening result for a leasing manager in 2 sentences. Return {\"summary\": string}. State the income-to-rent ratio, credit score and background result. "
                    + "Do not consider or mention protected characteristics. The decision belongs to a person.";
            case "triage" -> base + "Triage a maintenance request. Return {\"category\": one of PLUMBING, HVAC, ELECTRICAL, APPLIANCE, PEST, GENERAL, \"priority\": one of EMERGENCY, HIGH, NORMAL, LOW, "
                    + "\"estimate\": typical US repair cost in dollars as a number, \"summary\": one sentence}. Gas smell, fire, sparking, flooding or no heat in cold weather are EMERGENCY.";
            case "thread" -> base + "You help a property manager catch up on a conversation between parties (residents, owners, vendors, staff). Return {\"summary\": 2 sentences, "
                    + "\"openQuestions\": array of up to 3 short strings for things still unanswered, \"suggestedReply\": a polite, concise reply (under 60 words) the next staff member could send}. "
                    + "Only use facts in the messages. Never promise payment terms, legal outcomes or repairs that the messages do not already commit to. Never mention or imply anyone's protected characteristics.";
            default -> base;
        };
    }

    private void validate(String c, JsonNode out) {
        switch (c) {
            case "listing" -> {
                String t = out.path("title").asText(""), d = out.path("description").asText("");
                if (t.isBlank() || d.length() < 40) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Incomplete listing copy");
                List<String> bad = guardrails.fairHousing(t, d);
                if (!bad.isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FAIR_HOUSING", "Generated copy was blocked for fair housing wording: " + bad);
            }
            case "lead-reply" -> {
                if (out.path("reply").asText("").isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Empty reply");
                if (!guardrails.fairHousing(out.path("reply").asText()).isEmpty()) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FAIR_HOUSING", "Reply blocked");
            }
            case "screening" -> {
                if (out.path("summary").asText("").isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Empty summary");
            }
            case "thread" -> {
                if (out.path("summary").asText("").isBlank()) throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Empty summary");
                if (!guardrails.fairHousing(out.path("summary").asText(), out.path("suggestedReply").asText("")).isEmpty())
                    throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "FAIR_HOUSING", "Suggestion blocked");
            }
            case "triage" -> {
                if (!List.of("PLUMBING", "HVAC", "ELECTRICAL", "APPLIANCE", "PEST", "GENERAL").contains(out.path("category").asText()))
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Unknown category");
                if (!List.of("EMERGENCY", "HIGH", "NORMAL", "LOW").contains(out.path("priority").asText()))
                    throw new ApiException(HttpStatus.BAD_GATEWAY, "AI_INVALID", "Unknown priority");
            }
            default -> { }
        }
    }

    private static String stripFence(String s) {
        String t = s.trim();
        if (t.startsWith("```")) t = t.replaceFirst("^```(json)?", "").replaceFirst("```$", "").trim();
        return t;
    }

    private static String trim(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
