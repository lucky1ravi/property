package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.AiClient;
import com.propertyos.common.config.ConfigClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewModerationTest {
    private static final ObjectMapper M = new ObjectMapper();

    /** An AI client that answers with a fixed reply, or is unavailable when the reply is null. */
    private static ReviewModeration with(String aiReply) throws Exception {
        AiClient ai = new AiClient(null) {
            @Override public Optional<JsonNode> call(String capability, Map<String, ?> input) {
                try { return aiReply == null ? Optional.empty() : Optional.of(M.readTree(aiReply)); } catch (Exception e) { throw new IllegalStateException(e); }
            }
        };
        return new ReviewModeration(ai, new ConfigClient(null, M));
    }

    @Test
    @DisplayName("the AI's score is used, and a score under 70 is not shown")
    void aiScore() throws Exception {
        ReviewModeration.Result low = with("{\"score\": 42, \"reasons\": [\"Insults the staff\"]}").score("Some comment");
        assertThat(low.score()).isEqualTo(42);
        assertThat(low.source()).isEqualTo("AI");
        assertThat(low.reasons()).containsExactly("Insults the staff");
        assertThat(ReviewModeration.visible(low.score(), 70)).isFalse();
        assertThat(ReviewModeration.visible(70, 70)).isTrue();
        assertThat(ReviewModeration.visible(95, 70)).isTrue();
    }

    @Test
    @DisplayName("an out-of-range AI score is clamped to 0 to 100")
    void clamped() throws Exception {
        assertThat(with("{\"score\": 150}").score("x").score()).isEqualTo(100);
        assertThat(with("{\"score\": -5}").score("x").score()).isEqualTo(0);
    }

    @Test
    @DisplayName("a comment that was never scored is not shown")
    void unscored() {
        assertThat(ReviewModeration.visible(null, 70)).isFalse();
    }

    @Test
    @DisplayName("a staff decision beats the AI score; with none, the score decides")
    void override() {
        assertThat(ReviewModeration.visible(10, "SHOW", 70)).isTrue();
        assertThat(ReviewModeration.visible(null, "SHOW", 70)).isTrue();
        assertThat(ReviewModeration.visible(99, "HIDE", 70)).isFalse();
        assertThat(ReviewModeration.visible(99, null, 70)).isTrue();
        assertThat(ReviewModeration.visible(30, null, 70)).isFalse();
    }

    @Test
    @DisplayName("blank comments are not scored: there is nothing to hide")
    void blank() throws Exception {
        assertThat(with("{\"score\": 10}").score("   ")).isNull();
        assertThat(with("{\"score\": 10}").score(null)).isNull();
    }

    @Test
    @DisplayName("with the AI unavailable, rules keep clean criticism and hide abuse, threats and contact details")
    void rules() throws Exception {
        ReviewModeration m = with(null);
        assertThat(m.score("The heating was broken for two weeks and nobody answered. Very disappointing.").score()).isGreaterThanOrEqualTo(70);
        ReviewModeration.Result abuse = m.score("The manager is a complete idiot");
        assertThat(abuse.source()).isEqualTo("RULES");
        assertThat(ReviewModeration.visible(abuse.score(), 70)).isFalse();
        assertThat(ReviewModeration.visible(m.score("Call me on 512-555-0199 or mail jo@example.com").score(), 70)).isFalse();
        assertThat(ReviewModeration.visible(m.score("I will burn down this place, watch your back").score(), 70)).isFalse();
    }

    @Test
    @DisplayName("the minimum score is 70 unless the operator changed it")
    void minScore() throws Exception {
        assertThat(with(null).minScore(null)).isEqualTo(70);
    }
}
