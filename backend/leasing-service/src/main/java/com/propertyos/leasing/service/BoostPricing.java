package com.propertyos.leasing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * What a company pays for a boost. The rules, in order:
 * <ol>
 *   <li>The base price is the price agreed with that company for the period if there is one, otherwise the standard price.</li>
 *   <li>The company's plan discount (a percentage) applies to the standard price only. A price agreed with the company is final and is not discounted again.</li>
 *   <li>One promo code, if given, comes off last: a percentage of what is left, or a fixed amount.</li>
 *   <li>The total is never below {@link #MINIMUM} (a boost is never free through discounts; a free boost is a grant from the operator).</li>
 * </ol>
 * Pure arithmetic, with the reasons in plain words so they can be shown on the quote and written on the invoice.
 */
final class BoostPricing {
    private BoostPricing() {
    }

    /** The least a purchased boost can cost. */
    static final BigDecimal MINIMUM = new BigDecimal("1.00");

    /** A promo code that has already been checked: a percentage or a fixed amount off. */
    record Promo(String code, Integer percentOff, BigDecimal amountOff) {
    }

    /**
     * @param listPrice the standard price for the period
     * @param total     what the company pays
     * @param notes     one line per adjustment, in order
     */
    record Quote(BigDecimal listPrice, BigDecimal total, List<String> notes) {
        BigDecimal saving() {
            return listPrice.subtract(total);
        }

        /** The notes as one sentence for an invoice or a record. */
        String summary() {
            return notes.isEmpty() ? "" : String.join("; ", notes);
        }
    }

    private static BigDecimal usd(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }

    static Quote quote(BigDecimal list, BigDecimal agreed, int planDiscountPercent, String plan, Promo promo) {
        BigDecimal listPrice = usd(list);
        List<String> notes = new ArrayList<>();
        BigDecimal price = listPrice;
        if (agreed != null) {
            price = usd(agreed);
            notes.add("Agreed price " + dollars(price) + " instead of " + dollars(listPrice));
        } else if (planDiscountPercent > 0) {
            int pct = Math.min(100, planDiscountPercent);
            BigDecimal off = usd(price.multiply(BigDecimal.valueOf(pct)).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP));
            price = price.subtract(off);
            notes.add((plan == null || plan.isBlank() ? "Plan" : plan) + " discount " + pct + "% (-" + dollars(off) + ")");
        }
        if (promo != null) {
            BigDecimal off = promo.percentOff() != null
                    ? usd(price.multiply(BigDecimal.valueOf(promo.percentOff())).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP))
                    : usd(promo.amountOff()).min(price);
            price = price.subtract(off);
            notes.add("Code " + promo.code() + " " + (promo.percentOff() != null ? promo.percentOff() + "%" : dollars(promo.amountOff())) + " (-" + dollars(off) + ")");
        }
        if (price.compareTo(MINIMUM) < 0) {
            price = MINIMUM;
            notes.add("Minimum charge " + dollars(MINIMUM));
        }
        return new Quote(listPrice, usd(price), notes);
    }

    private static String dollars(BigDecimal v) {
        return "$" + usd(v).toPlainString();
    }
}
