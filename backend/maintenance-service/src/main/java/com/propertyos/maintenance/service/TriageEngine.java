package com.propertyos.maintenance.service;

import java.math.BigDecimal;
import java.util.*;

/** Rule-based triage used when the AI service is unavailable, and as a safety floor under AI results (emergencies are never downgraded). */
public final class TriageEngine {
    private TriageEngine() {
    }

    /**
     * A suggested classification.
     *
     * @param category   trade such as PLUMBING or HVAC
     * @param priority   suggested urgency
     * @param estimate   rough cost in USD
     * @param safetyRisk true when the text suggests danger such as gas, fire or flooding
     * @param summary    one-line explanation
     * @param tips       immediate steps for the resident
     */
    public record Result(String category, String priority, BigDecimal estimate, boolean safetyRisk, String summary, List<String> tips) {
    }

    /**
     * Words that point to each trade category.
     */
    private static final Map<String, List<String>> KEYWORDS = new LinkedHashMap<>();

    static {
        KEYWORDS.put("PLUMBING", List.of("gas", "leak", "pipe", "toilet", "faucet", "drain", "clog", "water heater", "no hot water", "flood", "sink", "shower"));
        KEYWORDS.put("HVAC", List.of("ac ", "a/c", "air condition", "heat", "furnace", "thermostat", "hvac", "no cooling", "no heat"));
        KEYWORDS.put("ELECTRICAL", List.of("outlet", "breaker", "power", "light", "spark", "electric", "wiring", "switch"));
        KEYWORDS.put("APPLIANCE", List.of("fridge", "refrigerator", "dishwasher", "oven", "stove", "washer", "dryer", "microwave", "disposal"));
        KEYWORDS.put("PEST", List.of("roach", "ants", "mice", "rat ", "bed bug", "pest", "termite", "wasp"));
    }

    /**
     * Words that make a request an emergency or a safety risk.
     */
    private static final List<String> EMERGENCY = List.of("gas smell", "smell gas", "gas leak", "fire", "smoke", "sparking", "burst", "flooding", "no heat in winter",
            "carbon monoxide", "sewage", "break-in", "broken lock", "exposed wire");

    /**
     * Classifies a request by keyword scoring (no AI needed).
     *
     * @param title       the request title
     * @param description details, may be null
     * @return the best-matching category with priority and estimate
     */
    public static Result classify(String title, String description) {
        String text = (title + " " + (description == null ? "" : description)).toLowerCase() + " ";
        String category = "GENERAL";
        int best = 0;
        for (var e : KEYWORDS.entrySet()) {
            int hits = (int) e.getValue().stream().filter(text::contains).count();
            if (hits > best) {
                best = hits;
                category = e.getKey();
            }
        }
        boolean emergency = EMERGENCY.stream().anyMatch(text::contains);
        String priority = emergency ? "EMERGENCY"
                : text.contains("no hot water") || text.contains("not working") || text.contains("no cooling") || text.contains("no heat") || text.contains("leak") ? "HIGH"
                : text.contains("cosmetic") || text.contains("paint") || text.contains("squeak") ? "LOW" : "NORMAL";
        BigDecimal estimate = switch (category) {
            case "PLUMBING" -> BigDecimal.valueOf(emergency ? 650 : 280);
            case "HVAC" -> BigDecimal.valueOf(420);
            case "ELECTRICAL" -> BigDecimal.valueOf(emergency ? 520 : 310);
            case "APPLIANCE" -> BigDecimal.valueOf(240);
            case "PEST" -> BigDecimal.valueOf(180);
            default -> BigDecimal.valueOf(150);
        };
        List<String> tips = new ArrayList<>();
        if (emergency) tips.add("If you smell gas or see smoke, leave the unit and call 911 first.");
        if (category.equals("PLUMBING") && text.contains("leak")) tips.add("Shut off the water valve under the fixture if you can.");
        if (category.equals("HVAC")) tips.add("Check that the thermostat is set to the right mode and the filter isn't blocked.");
        if (category.equals("ELECTRICAL")) tips.add("Check the breaker panel for a tripped breaker. Do not touch exposed wires.");
        String summary = (emergency ? "Safety risk detected. " : "") + "Looks like a " + category.toLowerCase() + " issue, " + priority.toLowerCase() + " priority, estimated around $" + estimate + ".";
        return new Result(category, priority, estimate, emergency, summary, tips);
    }

    /** Response-time targets by priority, in hours. */
    public static int slaHours(String priority) {
        return switch (priority) {
            case "EMERGENCY" -> 4;
            case "HIGH" -> 24;
            case "LOW" -> 168;
            default -> 72;
        };
    }
}
