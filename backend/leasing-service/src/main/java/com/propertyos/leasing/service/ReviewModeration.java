package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.config.ConfigClient;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Decides whether a resident's written comment is fit to show on the public listing page. The AI gives the comment a score from 0 to 100; a
 * comment scoring below the minimum (70 by default, the PropertyOS operator's {@code reviewModeration.minPublicScore}) is not shown to visitors. The
 * star rating itself always counts. When the AI is unavailable or switched off, simple rules give the score instead, so abuse never goes live
 * just because the model is down.
 */
@Service
public class ReviewModeration {
    /** A score and where it came from: {@code AI} or {@code RULES}. */
    public record Result(int score, String source, List<String> reasons) {
    }

    static final int DEFAULT_MIN_SCORE = 70;

    private final AiClient ai;
    private final ConfigClient config;

    public ReviewModeration(AiClient ai, ConfigClient config) {
        this.ai = ai;
        this.config = config;
    }

    /** Scores a comment. Blank comments are not scored (null): there is nothing to show or hide. */
    public Result score(String comment) {
        if (comment == null || comment.isBlank()) return null;
        Optional<JsonNode> out = ai.call("review-moderation", Map.of("comment", comment));
        if (out.isPresent() && out.get().path("score").isNumber()) {
            int s = Math.max(0, Math.min(100, out.get().path("score").asInt()));
            List<String> reasons = new ArrayList<>();
            out.get().path("reasons").forEach(r -> { if (r.isTextual() && reasons.size() < 3) reasons.add(r.asText()); });
            return new Result(s, "AI", reasons);
        }
        return byRules(comment);
    }

    /** The lowest score that may be shown publicly. Platform-wide: read from the product configuration, never from a company's own settings. */
    public int minScore(UUID org) {
        JsonNode cfg = org == null ? config.defaults() : config.forOrg(org);
        JsonNode n = cfg.path("reviewModeration").path("minPublicScore");
        if (!n.isNumber()) n = config.defaults().path("reviewModeration").path("minPublicScore");
        return n.isNumber() ? Math.max(0, Math.min(100, n.asInt())) : DEFAULT_MIN_SCORE;
    }

    /** True when a comment with this score is shown to visitors. A comment that was never scored is not shown until it is. */
    public static boolean visible(Integer score, int minScore) {
        return score != null && score >= minScore;
    }

    /** Whether a comment is shown: a staff decision (SHOW or HIDE) wins; otherwise the AI score must reach the minimum. */
    public static boolean visible(Integer score, String override, int minScore) {
        if ("SHOW".equals(override)) return true;
        if ("HIDE".equals(override)) return false;
        return visible(score, minScore);
    }

    // ---- the fallback rules: deliberately blunt. They only need to keep obvious abuse and personal details off the public page.

    private static final Pattern INSULT = Pattern.compile("\\b(fuck\\w*|shit\\w*|bitch\\w*|asshole\\w*|bastard\\w*|dick\\w*|cunt\\w*|idiots?|morons?|scum\\w*|pieces? of crap|pieces? of shit)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern THREAT = Pattern.compile("\\b(kill|hurt|shoot|burn down|beat)\\s+(you|him|her|them|everyone)\\b|\\bwatch your back\\b|\\bi know where you live\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTACT = Pattern.compile("[\\w.+-]+@[\\w-]+\\.[\\w.]+|\\+?\\d[\\d\\s().-]{8,}\\d|https?://\\S+|www\\.\\S+", Pattern.CASE_INSENSITIVE);

    static Result byRules(String comment) {
        int score = 100;
        List<String> reasons = new ArrayList<>();
        if (THREAT.matcher(comment).find()) { score -= 70; reasons.add("Contains a threat."); }
        if (INSULT.matcher(comment).find()) { score -= 45; reasons.add("Contains abusive language."); }
        if (CONTACT.matcher(comment).find()) { score -= 40; reasons.add("Contains contact details or a link."); }
        long letters = comment.chars().filter(Character::isLetter).count();
        long upper = comment.chars().filter(Character::isUpperCase).count();
        if (letters >= 20 && upper * 10 > letters * 7) { score -= 15; reasons.add("Mostly capital letters."); }
        return new Result(Math.max(0, score), "RULES", reasons);
    }
}
