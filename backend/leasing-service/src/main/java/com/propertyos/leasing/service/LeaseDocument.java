package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Generates the residential lease from the organisation's template, applying the state's
 * rules (deposit cap, late fee grace period and cap, refund deadline, required disclosures).
 * State rule values are configurable defaults and should be confirmed by counsel.
 */
public final class LeaseDocument {
    private LeaseDocument() {
    }

    /**
     * Date format used inside lease text, for example "March 1, 2026".
     */
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US);

    /**
     * Notices a state requires in a lease, by state code.
     */
    private static final Map<String, List<String>> DISCLOSURES = Map.of(
            "CA", List.of("Megan's Law database notice (Civil Code 2079.10a)", "Mold and dampness disclosure",
                    "Bed bug information (Civil Code 1954.603)", "Smoking policy disclosure"),
            "TX", List.of("Parking and towing rules (Property Code 92.0131)", "Right to request repairs in writing (Property Code 92.056)",
                    "Owner and management contact disclosure (Property Code 92.201)"),
            "NY", List.of("Bed bug infestation history", "Sprinkler system disclosure", "Lead-based paint disclosure where applicable"),
            "FL", List.of("Radon gas disclosure (Statute 404.056)", "Security deposit holding disclosure (Statute 83.49)"),
            "WA", List.of("Mold disclosure (RCW 59.18.060)", "Fire safety and smoke detection disclosure", "Deposit checklist (RCW 59.18.260)")
    );

    /**
     * The generated lease.
     *
     * @param text        full lease text
     * @param deposit     deposit actually charged after state limits
     * @param adjustments notes about any change made to comply with state rules
     * @param terms       key terms for display
     */
    public record Result(String text, BigDecimal deposit, List<String> adjustments, Map<String, Object> terms) {
    }

    /**
     * Builds lease text from the parties, dates, rent and the state's rules (deposit cap, required notices) and the company's fee settings.
     *
     * @param company  the management company
     * @param tenantName resident's name
     * @param propertyName building name
     * @param address  street address
     * @param unitLabel unit
     * @param start    lease start
     * @param end      lease end
     * @param rent     monthly rent in USD
     * @param requestedDeposit deposit asked for
     * @param state    two-letter US state code
     * @param rule     the state's rule set
     * @param fees     the company's fee settings
     * @param pets     pet terms, or null
     * @return the generated lease
     */
    public static Result generate(String company, String tenantName, String propertyName, String address, String unitLabel,
                                  LocalDate start, LocalDate end, BigDecimal rent, BigDecimal requestedDeposit, String state,
                                  JsonNode rule, JsonNode fees, String pets) {
        List<String> adjustments = new ArrayList<>();
        BigDecimal deposit = requestedDeposit;
        if (rule.hasNonNull("depositCapMonths")) {
            BigDecimal cap = rent.multiply(BigDecimal.valueOf(rule.get("depositCapMonths").asDouble())).setScale(2, RoundingMode.HALF_UP);
            if (deposit.compareTo(cap) > 0) {
                adjustments.add("Security deposit reduced from " + usd(deposit) + " to " + usd(cap) + " to meet the "
                        + rule.path("name").asText(state) + " cap of " + rule.get("depositCapMonths").asText() + " month(s) of rent.");
                deposit = cap;
            }
        }
        int grace = rule.path("lateFeeGraceDays").asInt(5);
        BigDecimal lateFee = lateFee(rent, rule, fees);
        int refundDays = rule.path("depositRefundDays").asInt(30);
        String lateFeeText = usd(lateFee) + " if rent is not received within " + grace + " days of the due date";

        StringBuilder sb = new StringBuilder();
        sb.append("RESIDENTIAL LEASE AGREEMENT\n\n");
        sb.append("This lease is made on ").append(LocalDate.now().format(D)).append(" between ").append(company)
                .append(" (\"Landlord\"), as managing agent for the owner, and ").append(tenantName).append(" (\"Tenant\").\n\n");
        sb.append("1. PREMISES. ").append(propertyName).append(", ").append(unitLabel).append(", ").append(address).append(".\n\n");
        sb.append("2. TERM. The lease begins on ").append(start.format(D)).append(" and ends on ").append(end.format(D))
                .append(". Either party may decline renewal with at least ").append(rule.path("noticeDays").asInt(30)).append(" days' written notice.\n\n");
        sb.append("3. RENT. Tenant will pay ").append(usd(rent)).append(" per month, due on the 1st day of each month, through the PropertyOS resident portal (ACH or card).\n\n");
        sb.append("4. LATE FEE. A late fee of ").append(lateFeeText).append(". The fee complies with ").append(rule.path("name").asText(state)).append(" limits.\n\n");
        sb.append("5. SECURITY DEPOSIT. Tenant will pay a security deposit of ").append(usd(deposit))
                .append(". Landlord will return the deposit, less itemised lawful deductions, within ").append(refundDays).append(" days after move-out.\n\n");
        sb.append("6. UTILITIES. Tenant pays electricity, internet and gas. Water, sewer and trash are billed back monthly as invoiced.\n\n");
        sb.append("7. PETS. ").append(pets == null || pets.isBlank() || pets.equalsIgnoreCase("none") ? "No pets without written consent." :
                "Approved pets: " + pets + ". Monthly pet rent per the fee schedule applies.").append("\n\n");
        sb.append("8. MAINTENANCE. Tenant reports repairs through the resident portal. Emergencies (gas, flooding, no heat, electrical hazards) are handled 24/7.\n\n");
        sb.append("9. ENTRY. Landlord will give at least 24 hours' notice before entering, except in an emergency.\n\n");
        sb.append("10. DISCLOSURES. ");
        List<String> d = new ArrayList<>(DISCLOSURES.getOrDefault(state, List.of()));
        d.add("Lead-based paint disclosure (federal, for housing built before 1978)");
        sb.append(String.join("; ", d)).append(".\n\n");
        sb.append("11. FAIR HOUSING. Landlord does not discriminate on the basis of race, color, religion, sex, national origin, familial status or disability, or any class protected by state or local law.\n\n");
        sb.append("12. ELECTRONIC SIGNATURE. The parties agree that electronic signatures in PropertyOS are binding under the federal E-SIGN Act.\n");

        Map<String, Object> terms = new java.util.LinkedHashMap<>();
        terms.put("lateFee", lateFee);
        terms.put("lateFeeGraceDays", grace);
        terms.put("depositRefundDays", refundDays);
        terms.put("noticeDays", rule.path("noticeDays").asInt(30));
        terms.put("pets", pets == null ? "None" : pets);
        terms.put("disclosures", d);
        return new Result(sb.toString(), deposit, adjustments, terms);
    }

    /** Late fee: organisation's formula, capped by the state's percentage and flat caps. */
    public static BigDecimal lateFee(BigDecimal rent, JsonNode rule, JsonNode fees) {
        JsonNode lf = fees.path("lateFee");
        BigDecimal fee = "FLAT".equals(lf.path("type").asText("PERCENT"))
                ? BigDecimal.valueOf(lf.path("flat").asDouble(75))
                : rent.multiply(BigDecimal.valueOf(lf.path("percent").asDouble(5))).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        if (rule.hasNonNull("lateFeeCapPercent")) {
            BigDecimal cap = rent.multiply(BigDecimal.valueOf(rule.get("lateFeeCapPercent").asDouble())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            fee = fee.min(cap);
        }
        if (rule.hasNonNull("lateFeeCapFlat")) fee = fee.min(BigDecimal.valueOf(rule.get("lateFeeCapFlat").asDouble()));
        return fee.setScale(2, RoundingMode.HALF_UP);
    }

    static String usd(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(v);
    }
}
