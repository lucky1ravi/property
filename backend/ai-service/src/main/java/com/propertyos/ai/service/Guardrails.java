package com.propertyos.ai.service;

import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Safety layer around every model call: prompt-injection screening, PII redaction, fair-housing screening of generated
 * text, and per-user rate limiting. Guardrails run in code, outside the model, so they cannot be talked around.
 */
@Component
public class Guardrails {
    private static final List<Pattern> INJECTION = List.of(
            Pattern.compile("ignore (all |any |the )?(previous|prior|above) (instructions|rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(reveal|show|print|repeat).{0,30}(system prompt|instructions|your rules)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(developer|dan|jailbreak) mode", Pattern.CASE_INSENSITIVE),
            Pattern.compile("pretend (you are|to be) (an? )?(admin|administrator|superuser)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("act as (an? )?(admin|administrator|root)", Pattern.CASE_INSENSITIVE),
            Pattern.compile("(bypass|disable|turn off).{0,20}(security|guardrails|permissions|row.level)", Pattern.CASE_INSENSITIVE));
    private static final Pattern SSN = Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");
    private static final Pattern CARD = Pattern.compile("\\b(?:\\d[ -]?){13,16}\\b");
    private static final List<String> FAIR_HOUSING = List.of("no kids", "no children", "adults only", "christian", "english only", "no section 8", "perfect for singles",
            "no families", "able-bodied", "no wheelchairs", "young professionals only", "mature", "ideal for a couple", "walking distance to church", "no immigrants");
    private final Map<String, Deque<Long>> hits = new ConcurrentHashMap<>();

    public Optional<String> injection(String text) {
        for (Pattern p : INJECTION) if (p.matcher(text).find()) return Optional.of("Possible attempt to override the assistant's rules");
        return Optional.empty();
    }

    public String redact(String text) {
        if (text == null) return null;
        return CARD.matcher(SSN.matcher(text).replaceAll("***-**-****")).replaceAll("[card number removed]");
    }

    public List<String> fairHousing(String... texts) {
        List<String> found = new ArrayList<>();
        String all = String.join(" ", texts).toLowerCase();
        for (String b : FAIR_HOUSING) if (all.contains(b)) found.add(b);
        return found;
    }

    /** True when the user is within 20 requests per minute. */
    public boolean allow(String userKey) {
        long now = System.currentTimeMillis();
        Deque<Long> q = hits.computeIfAbsent(userKey, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && now - q.peekFirst() > 60_000) q.pollFirst();
            if (q.size() >= 20) return false;
            q.addLast(now);
            return true;
        }
    }
}
