package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class BoostPricingTest {
    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    private static BoostPricing.Quote q(String list, String agreed, int plan, BoostPricing.Promo promo) {
        return BoostPricing.quote(d(list), agreed == null ? null : d(agreed), plan, "ENTERPRISE", promo);
    }

    @Test
    @DisplayName("with no discount of any kind the company pays the standard price")
    void standard() {
        BoostPricing.Quote x = q("49", null, 0, null);
        assertThat(x.total()).isEqualByComparingTo("49.00");
        assertThat(x.notes()).isEmpty();
        assertThat(x.saving()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a plan discount takes a percentage off the standard price")
    void planDiscount() {
        BoostPricing.Quote x = q("149", null, 20, null);
        assertThat(x.total()).isEqualByComparingTo("119.20");
        assertThat(x.notes()).hasSize(1);
        assertThat(x.notes().get(0)).contains("ENTERPRISE discount 20%").contains("$29.80");
    }

    @Test
    @DisplayName("a price agreed with the company is final: the plan discount is not applied on top")
    void agreedPrice() {
        BoostPricing.Quote x = q("149", "99", 20, null);
        assertThat(x.total()).isEqualByComparingTo("99.00");
        assertThat(x.listPrice()).isEqualByComparingTo("149.00");
        assertThat(x.notes().get(0)).contains("Agreed price $99.00 instead of $149.00");
    }

    @Test
    @DisplayName("a percentage promo code comes off after the plan discount")
    void promoPercent() {
        BoostPricing.Quote x = q("100", null, 10, new BoostPricing.Promo("SPRING", 25, null));
        // 100 less 10% is 90, less 25% of 90 (22.50) is 67.50
        assertThat(x.total()).isEqualByComparingTo("67.50");
        assertThat(x.notes()).hasSize(2);
        assertThat(x.notes().get(1)).contains("Code SPRING 25%").contains("$22.50");
    }

    @Test
    @DisplayName("a fixed-amount promo code comes off the price, and can also follow an agreed price")
    void promoAmount() {
        assertThat(q("49", null, 0, new BoostPricing.Promo("FIVE", null, d("5"))).total()).isEqualByComparingTo("44.00");
        assertThat(q("149", "99", 20, new BoostPricing.Promo("FIVE", null, d("5"))).total()).isEqualByComparingTo("94.00");
    }

    @Test
    @DisplayName("a boost bought with discounts never costs less than $1: a free boost is a grant from the operator")
    void minimum() {
        BoostPricing.Quote x = q("49", null, 0, new BoostPricing.Promo("ALL", 100, null));
        assertThat(x.total()).isEqualByComparingTo("1.00");
        assertThat(x.notes()).anyMatch(n -> n.contains("Minimum charge"));
        assertThat(q("49", null, 0, new BoostPricing.Promo("HUGE", null, d("500"))).total()).isEqualByComparingTo("1.00");
    }

    @Test
    @DisplayName("the notes read as one sentence for the invoice")
    void summary() {
        assertThat(q("100", null, 10, new BoostPricing.Promo("SPRING", 25, null)).summary()).contains("; ").contains("Code SPRING");
        assertThat(q("100", null, 0, null).summary()).isEmpty();
    }

    @Test
    @DisplayName("boost settings read the on/off switch and the plan discounts, and stay safe on bad input")
    void settings() throws Exception {
        ObjectMapper m = new ObjectMapper();
        ListingRanking.Boost b = ListingRanking.Boost.of(m.readTree("{\"onSale\":false,\"planDiscountPercent\":{\"ENTERPRISE\":20,\"GROWTH\":150,\"STARTER\":\"x\"}}"));
        assertThat(b.enabled()).isFalse();
        assertThat(b.discountFor("ENTERPRISE")).isEqualTo(20);
        assertThat(b.discountFor("GROWTH")).isEqualTo(100);   // clamped
        assertThat(b.discountFor("STARTER")).isEqualTo(0);    // not a number: ignored
        assertThat(b.discountFor("NOPE")).isEqualTo(0);
        assertThat(ListingRanking.Boost.of(null).enabled()).isTrue();
        assertThat(ListingRanking.Boost.of(m.readTree("{}")).enabled()).isTrue();
    }
}
