package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ListingRankingTest {
    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String s) throws Exception {
        return M.readTree(s);
    }

    private static final String PLANS = "{\"STARTER\":{\"pricePerUnit\":1.25},\"GROWTH\":{\"pricePerUnit\":1.75},\"ENTERPRISE\":{\"pricePerUnit\":2.5}}";

    @Test
    @DisplayName("plan tier runs from 0 for the cheapest plan to 1 for the dearest")
    void tier() throws Exception {
        JsonNode plans = json(PLANS);
        assertThat(ListingRanking.tier(plans, "STARTER")).isEqualTo(0);
        assertThat(ListingRanking.tier(plans, "GROWTH")).isEqualTo(0.4, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(ListingRanking.tier(plans, "ENTERPRISE")).isEqualTo(1);
    }

    @Test
    @DisplayName("an unknown plan, no plans, or plans that all cost the same give tier 0")
    void tierEdges() throws Exception {
        assertThat(ListingRanking.tier(json(PLANS), "NOPE")).isEqualTo(0);
        assertThat(ListingRanking.tier(null, "GROWTH")).isEqualTo(0);
        assertThat(ListingRanking.tier(json("{\"A\":{\"pricePerUnit\":2},\"B\":{\"pricePerUnit\":2}}"), "A")).isEqualTo(0);
    }

    @Test
    @DisplayName("missing settings use the defaults")
    void defaults() {
        ListingRanking.Settings s = ListingRanking.settings(null);
        assertThat(s.totalWeight()).isEqualTo(100);
        assertThat(s.priorMean()).isEqualTo(4.0);
        assertThat(s.priorWeight()).isEqualTo(5);
        assertThat(s.photosTarget()).isEqualTo(8);
    }

    @Test
    @DisplayName("configured values are read, and out-of-range or all-zero ones are clamped or replaced")
    void configured() throws Exception {
        ListingRanking.Settings s = ListingRanking.settings(json("{\"weights\":{\"plan\":50,\"rating\":50,\"freshness\":0,\"quality\":0},\"rating\":{\"priorMean\":9,\"priorWeight\":2,\"unitShare\":0.5},\"freshness\":{\"halfLifeDays\":0}}"));
        assertThat(s.wPlan()).isEqualTo(50);
        assertThat(s.priorMean()).isEqualTo(5);      // clamped to the 1 to 5 star scale
        assertThat(s.halfLifeDays()).isEqualTo(1);   // never zero: it is a divisor
        ListingRanking.Settings zero = ListingRanking.settings(json("{\"weights\":{\"plan\":0,\"rating\":0,\"freshness\":0,\"quality\":0}}"));
        assertThat(zero.totalWeight()).isEqualTo(100);
    }

    @Test
    @DisplayName("the SQL names only the configured numbers and the companies given, and the score is out of 100")
    void sql() {
        UUID org = UUID.randomUUID();
        ListingRanking.Settings s = ListingRanking.settings(null);
        String joins = ListingRanking.joins(s, Map.of(org, 0.4));
        assertThat(joins).contains("when '" + org + "' then 0.400000").contains("f_rating").contains("f_quality").doesNotContain("?");
        assertThat(ListingRanking.scoreSql(s)).contains("100 *").contains("f.f_plan");
    }

    @Test
    @DisplayName("each factor's points add up to the score, and the maxima add up to 100")
    void explain() {
        ListingRanking.Settings s = ListingRanking.settings(null);
        Map<String, Object> row = new java.util.HashMap<>(Map.of("f_plan", 1.0, "f_rating", 0.5, "f_fresh", 0.5, "f_quality", 1.0, "photos", 8, "desc_len", 300, "fields", 6,
                "age_days", 30.0));
        row.putAll(Map.of("p_n", 2, "p_s", 9, "u_n", 0, "u_s", 0));
        List<Map<String, Object>> lines = ListingRanking.explain(s, row, "Enterprise");
        assertThat(lines).hasSize(4);
        assertThat(lines.stream().mapToDouble(l -> ((Number) l.get("max")).doubleValue()).sum()).isEqualTo(100.0);
        assertThat(lines.stream().mapToDouble(l -> ((Number) l.get("points")).doubleValue()).sum()).isEqualTo(15 + 17.5 + 10 + 30);
        assertThat((String) lines.get(1).get("detail")).contains("4.5 from 2 reviews").contains("no reviews yet");
    }

    @Test
    @DisplayName("the weakest factor is the one furthest below its best, ignoring the plan; nothing is named when all are near their best")
    void weakest() {
        ListingRanking.Settings s = ListingRanking.settings(null);
        // rating 0.9 (gap 3.5), freshness 0.2 (gap 16), quality 0.5 (gap 15): freshness is furthest below its best
        assertThat(ListingRanking.weakest(s, Map.of("f_rating", 0.9, "f_fresh", 0.2, "f_quality", 0.5))).isEqualTo("Freshness");
        assertThat(ListingRanking.weakest(s, Map.of("f_rating", 0.95, "f_fresh", 0.95, "f_quality", 0.95))).isNull();
    }

    @Test
    @DisplayName("the Top rated sort uses the smoothed rating on the 1 to 5 scale")
    void ratingRank() {
        assertThat(ListingRanking.ratingRankSql()).contains("f.f_rating * 4 + 1");
    }

    @Test
    @DisplayName("boost prices come from the configuration, in order, and a mistyped price is skipped")
    void boostPrices() throws Exception {
        ListingRanking.Boost b = ListingRanking.Boost.of(json("{\"prices\":{\"30\":149,\"7\":49,\"14\":\"89\",\"x\":5,\"60\":0,\"90\":-1},\"bonusPoints\":15,\"maxSharePercent\":30}"));
        assertThat(b.prices().keySet()).containsExactly(7, 14, 30);
        assertThat(b.prices().get(7).toPlainString()).isEqualTo("49.00");
        assertThat(b.prices().get(14).toPlainString()).isEqualTo("89.00");
        assertThat(b.prices().get(30).toPlainString()).isEqualTo("149.00");
    }

    @Test
    @DisplayName("missing boost settings fall back to the defaults: 15 points, 30 percent of a page, 14 days to pay")
    void boostDefaults() {
        ListingRanking.Boost b = ListingRanking.Boost.of(null);
        assertThat(b.prices()).isEmpty();
        assertThat(b.bonusPoints()).isEqualTo(15);
        assertThat(b.maxSharePercent()).isEqualTo(30);
        assertThat(b.unpaidExpiryDays()).isEqualTo(14);
    }

    @Test
    @DisplayName("boosted homes cannot fill a page: the bonus goes to a limited number per page")
    void boostCap() throws Exception {
        ListingRanking.Boost b = ListingRanking.Boost.of(json("{\"bonusPoints\":15,\"maxSharePercent\":30}"));
        assertThat(b.capPerPage(20)).isEqualTo(6);
        assertThat(b.capPerPage(10)).isEqualTo(3);
        assertThat(b.capPerPage(2)).isEqualTo(1);    // never rounds down to nothing while boosts are allowed
        assertThat(ListingRanking.Boost.of(json("{\"bonusPoints\":15,\"maxSharePercent\":0}")).capPerPage(20)).isEqualTo(0);
        assertThat(ListingRanking.Boost.of(json("{\"bonusPoints\":0,\"maxSharePercent\":30}")).capPerPage(20)).isEqualTo(0);
        assertThat(ListingRanking.Boost.of(json("{\"bonusPoints\":15,\"maxSharePercent\":500}")).maxSharePercent()).isEqualTo(100);
    }

    @Test
    @DisplayName("the final score adds the bonus only to a boosted home within the cap")
    void finalScore() throws Exception {
        ListingRanking.Boost b = ListingRanking.Boost.of(json("{\"bonusPoints\":15,\"maxSharePercent\":30}"));
        String sql = ListingRanking.finalScoreSql(b, 6, "x.");
        assertThat(sql).contains("x.boosted = 1").contains("x.brank <= 6").contains("x.base_score + 15.000000").contains("else x.base_score");
    }

    @Test
    @DisplayName("the shared SQL knows whether a home has an active boost and ranks boosted homes among themselves")
    void boostSql() {
        ListingRanking.Settings s = ListingRanking.settings(null);
        assertThat(ListingRanking.joins(s, Map.of())).contains("leasing.listing_boost").contains("b.status = 'ACTIVE'").contains("b.ends_at > now()").contains("as boosted");
        assertThat(ListingRanking.boostRankSql(s)).startsWith("row_number() over (partition by f.boosted order by");
    }

    @Test
    @DisplayName("the explanation line for a boost shows the bonus, the end date and the page cap")
    void boostLine() throws Exception {
        ListingRanking.Boost b = ListingRanking.Boost.of(json("{\"bonusPoints\":15,\"maxSharePercent\":30}"));
        Map<String, Object> m = ListingRanking.boostLine(b, "2026-11-20", true);
        assertThat(m.get("points")).isEqualTo(15.0);
        assertThat((String) m.get("detail")).contains("2026-11-20").contains("30%");
        // Over the page cap the home stays Sponsored but earns no points, and the line says why.
        Map<String, Object> over = ListingRanking.boostLine(b, "2026-11-20", false);
        assertThat(over.get("points")).isEqualTo(0.0);
        assertThat(over.get("max")).isEqualTo(15.0);
        assertThat((String) over.get("detail")).contains("not applied").contains("Sponsored");
    }

    @Test
    @DisplayName("public review names show the first name and last initial only")
    void publicName() {
        assertThat(ListingRanking.publicName("Rae Resident")).isEqualTo("Rae R.");
        assertThat(ListingRanking.publicName("Madonna")).isEqualTo("Madonna");
        assertThat(ListingRanking.publicName("  ")).isEqualTo("A resident");
        assertThat(ListingRanking.publicName(null)).isEqualTo("A resident");
    }
}
