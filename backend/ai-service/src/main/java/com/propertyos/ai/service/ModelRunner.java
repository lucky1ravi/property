package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

import static com.propertyos.ai.service.StudioText.strip;

/**
 * The one place AI tools talk to a language model. It applies the per-company on/off switch, redacts personal data from
 * prompts, parses the JSON reply and records every call in the audit log. Tools depend on this class rather than on a
 * specific model, so a different provider changes nothing in the tools.
 */
@Component
public class ModelRunner {
    private static final Logger log = LoggerFactory.getLogger(ModelRunner.class);
    private final LlmProvider provider;
    private final Guardrails guardrails;
    private final ObjectMapper mapper;
    private final ConfigClient config;
    private final AiAudit audit;

    public ModelRunner(LlmProvider provider, Guardrails guardrails, ObjectMapper mapper, ConfigClient config, AiAudit audit) {
        this.provider = provider;
        this.guardrails = guardrails;
        this.mapper = mapper;
        this.config = config;
        this.audit = audit;
    }

    /** Refuses the call when the company has switched this tool off (configuration key {@code ai/<KEY>/enabled}). */
    void enabled(String key) {
        if (!config.flag("ai/" + key + "/enabled", true))
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AI_DISABLED", "This AI tool is turned off for your company.");
    }

    /** Asks the chat model for JSON. Returns null when no model is configured or it didn't return usable JSON. */
    JsonNode ask(String tool, String system, String user) {
        Optional<Llm> m = provider.get();
        if (m.isEmpty()) return null;
        long t0 = System.currentTimeMillis();
        try {
            Llm.Reply r = m.get().chat(system + " Reply with one JSON object only. Use only facts in the input; never invent numbers, dates or names.",
                    List.of(Llm.Msg.user(guardrails.redact(user))), List.of(), true);
            JsonNode out = mapper.readTree(strip(r.text()));
            audit.record("STUDIO", tool, user, List.of(), m.get().provider() + ":" + m.get().model(), r.inputTokens(), r.outputTokens(), false, null, false, System.currentTimeMillis() - t0);
            return out;
        } catch (Exception e) { // a model failure must never break the tool: the caller falls back to rules
            log.warn("{} model call failed, using rules: {}", tool, e.getMessage());
            audit.record("STUDIO", tool, user, List.of(), m.get().provider(), 0, 0, false, e.getMessage(), true, System.currentTimeMillis() - t0);
            return null;
        }
    }

    /** "provider:model" of the chat model in use, or "rules" when none is configured. */
    String modelName() {
        return provider.get().map(l -> l.provider() + ":" + l.model()).orElse("rules");
    }
}
