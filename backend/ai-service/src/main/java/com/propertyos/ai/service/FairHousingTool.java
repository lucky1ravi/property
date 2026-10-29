package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.propertyos.ai.service.StudioText.*;

/** Screens rental ads and messages for Fair Housing problems with a fixed phrase list plus a model, and suggests a rewrite that is itself re-checked. */
@Service
public class FairHousingTool {
    private final ModelRunner runner;
    private final Guardrails guardrails;

    public FairHousingTool(ModelRunner runner, Guardrails guardrails) {
        this.runner = runner;
        this.guardrails = guardrails;
    }

    // Fair housing check
    /**
     * Screens text for wording that could breach fair-housing rules and suggests safer wording.
     *
     * @param text the text to check (up to 4,000 characters)
     * @throws ApiException (400) when empty or too long
     */
    public Map<String, Object> check(String text) {
        runner.enabled("FAIR_HOUSING_CHECK");
        if (text == null || text.isBlank()) throw ApiException.badRequest("Paste the text you want checked.");
        if (text.length() > 4000) throw ApiException.badRequest("Keep the text under 4,000 characters.");
        List<String> hits = guardrails.fairHousing(text);
        Map<String, Object> out = new LinkedHashMap<>();
        JsonNode ai = runner.ask("fair-housing", "You review rental ads and messages for US Fair Housing Act problems (race, color, religion, sex, disability, familial status, national origin, plus common state classes such as source of income). "
                + "Return {\"issues\": [{\"phrase\": exact words from the text, \"why\": one sentence, \"protectedClass\": string}], \"rewrite\": the same text rewritten to remove the problems without changing the facts, \"verdict\": \"CLEAR\" or \"REVIEW\"}. "
                + "Describing the home is fine; describing who should live there is not. Reasonable accommodation language is fine.", text);
        List<Map<String, String>> issues = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String h : hits) { issues.add(Map.of("phrase", h, "why", "This wording is on the list of phrases that are commonly treated as discriminatory.", "protectedClass", "Listed phrase")); seen.add(h.toLowerCase()); }
        String rewrite = null;
        if (ai != null && ai.path("issues").isArray()) {
            for (JsonNode i : ai.path("issues")) {
                String p = i.path("phrase").asText("");
                if (!p.isBlank() && text.toLowerCase().contains(p.toLowerCase()) && seen.add(p.toLowerCase()))
                    issues.add(Map.of("phrase", p, "why", i.path("why").asText(""), "protectedClass", i.path("protectedClass").asText("")));
            }
            rewrite = ai.path("rewrite").asText(null);
            if (rewrite != null && !guardrails.fairHousing(rewrite).isEmpty()) rewrite = null;
            out.put("source", "AI+RULES");
            out.put("model", runner.modelName());
        } else out.put("source", "RULES");
        out.put("verdict", issues.isEmpty() ? "CLEAR" : "REVIEW");
        out.put("issues", issues);
        out.put("rewrite", issues.isEmpty() ? null : rewrite);
        out.put("note", "A helper, not legal advice. Have a person approve anything that goes public.");
        return out;
    }
}
