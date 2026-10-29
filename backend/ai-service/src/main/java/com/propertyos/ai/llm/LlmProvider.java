package com.propertyos.ai.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Chooses the model provider from environment variables, so keys never live in code or the database:
 * AI_PROVIDER=openai|bedrock|none, OPENAI_API_KEY, OPENAI_MODEL, OPENAI_BASE_URL, AWS_REGION, BEDROCK_MODEL_ID.
 * With no key the platform runs in offline mode: every AI feature falls back to rules.
 */
@Component
public class LlmProvider {
    private static final Logger log = LoggerFactory.getLogger(LlmProvider.class);
    private final Llm llm;
    private final Llm vision;

    public LlmProvider(ObjectMapper mapper) {
        String choice = env("AI_PROVIDER", "");
        String openAiKey = env("OPENAI_API_KEY", "");
        Llm built = null;
        try {
            if (("openai".equalsIgnoreCase(choice) || choice.isEmpty()) && !openAiKey.isBlank()) {
                built = new OpenAiLlm(mapper, openAiKey, env("OPENAI_MODEL", "gpt-4o-mini"), env("OPENAI_BASE_URL", "https://api.openai.com/v1"));
            } else if ("bedrock".equalsIgnoreCase(choice)) {
                built = new BedrockLlm(mapper, env("AWS_REGION", "us-east-1"), env("BEDROCK_MODEL_ID", "us.anthropic.claude-3-5-haiku-20241022-v1:0"));
            }
        } catch (Exception e) {
            log.warn("AI provider could not start, running in offline mode: {}", e.getMessage());
        }
        this.llm = built;
        Llm v = null;
        try {
            String visionModel = env("BEDROCK_VISION_MODEL_ID", "");
            if ("bedrock".equalsIgnoreCase(choice) && !visionModel.isBlank()) v = new BedrockLlm(mapper, env("AWS_REGION", "us-east-1"), visionModel, true);
            else if (built != null && built.provider().equals("openai") && built.supportsVision()) v = built;
        } catch (Exception e) {
            log.warn("Image model could not start, image checks use the built-in simulator: {}", e.getMessage());
        }
        this.vision = v;
        log.info("AI provider: {}", built == null ? "offline (rules only)" : built.provider() + " / " + built.model());
    }

    public Optional<Llm> get() {
        return Optional.ofNullable(llm);
    }

    /** The model used for image checks, if one that accepts images is configured. */
    public Optional<Llm> vision() {
        return Optional.ofNullable(vision);
    }

    public String describe() {
        return llm == null ? "offline" : llm.provider() + ":" + llm.model();
    }

    private static String env(String k, String d) {
        String v = System.getenv(k);
        return v == null || v.isBlank() ? d : v.trim();
    }
}
