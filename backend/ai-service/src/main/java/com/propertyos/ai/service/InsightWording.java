package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.config.ConfigClient;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites the explanation on each opportunity card in shorter, plainer words. The figures and the choice of cards stay with
 * the rules in {@link Insights}; the model only changes the wording, and its text is used only when every number in the
 * original sentence is still in it. Without a model, with the switch off, or when a rewrite fails the check, the card keeps the
 * rules' own sentence.
 */
@Component
public class InsightWording {
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d,]*(?:\\.\\d+)?");
    private static final int MAX_LENGTH = 200;
    private static final int CACHE_SIZE = 500;

    private final ModelRunner runner;
    private final ConfigClient config;
    private final ObjectMapper mapper;
    /** Original sentence to rewrite, so a card is only sent to the model once. */
    private final Map<String, String> cache = Collections.synchronizedMap(new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, String> e) {
            return size() > CACHE_SIZE;
        }
    });

    public InsightWording(ModelRunner runner, ConfigClient config, ObjectMapper mapper) {
        this.runner = runner;
        this.config = config;
        this.mapper = mapper;
    }

    /** Replaces {@code detail} on each item with a plainer version when one passes the check, and marks the item's {@code wording}. */
    public void rewrite(List<Map<String, Object>> items) {
        if (items.isEmpty() || !config.flag("ai/INSIGHT_WORDING/enabled", true)) return;
        List<Integer> todo = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) if (!cache.containsKey(detail(items.get(i)))) todo.add(i);
        if (!todo.isEmpty()) ask(items, todo);
        for (Map<String, Object> m : items) {
            String plain = cache.get(detail(m));
            if (plain == null) continue;
            m.put("detail", plain);
            m.put("wording", "AI");
        }
    }

    private void ask(List<Map<String, Object>> items, List<Integer> todo) {
        List<Map<String, Object>> input = new ArrayList<>();
        for (int i : todo) input.add(Map.of("id", i, "title", String.valueOf(items.get(i).get("title")), "detail", detail(items.get(i))));
        JsonNode out;
        try {
            out = runner.ask("insight-wording", "You rewrite the explanation on dashboard cards for a property manager. For each item rewrite \"detail\" in plain words, "
                    + "one or two short sentences and at most 25 words. Keep every number, dollar amount and name exactly as written, and add no new facts, promises or predictions. "
                    + "Return {\"items\":[{\"id\":0,\"detail\":\"...\"}]} with the same ids.", mapper.writeValueAsString(Map.of("items", input)));
        } catch (Exception e) {
            return;
        }
        if (out == null) return;
        for (JsonNode r : out.path("items")) {
            int id = r.path("id").asInt(-1);
            String plain = r.path("detail").asText("").trim();
            if (!todo.contains(id)) continue;
            String original = detail(items.get(id));
            if (acceptable(original, plain)) cache.put(original, plain);
        }
    }

    /** True when the new text is short, different, and keeps every number of the original. */
    static boolean acceptable(String original, String plain) {
        if (plain.isBlank() || plain.length() > MAX_LENGTH || plain.equals(original)) return false;
        return numbers(plain).containsAll(numbers(original));
    }

    private static Set<String> numbers(String s) {
        Set<String> out = new HashSet<>();
        Matcher m = NUMBER.matcher(s);
        while (m.find()) out.add(m.group().replace(",", ""));
        return out;
    }

    private static String detail(Map<String, Object> m) {
        return String.valueOf(m.get("detail"));
    }
}
