package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.config.ConfigClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;

class InsightWordingTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ORIGINAL = "Vacant 24 days, costing about $107 a day in lost rent. It's listed, so check the price and photos.";

    private final ModelRunner runner = Mockito.mock(ModelRunner.class);
    private final ConfigClient config = Mockito.mock(ConfigClient.class);

    private InsightWording wording(boolean enabled, String modelReply) throws Exception {
        Mockito.when(config.flag(anyString(), anyBoolean())).thenReturn(enabled);
        Mockito.when(runner.ask(anyString(), anyString(), anyString())).thenReturn(modelReply == null ? null : MAPPER.readTree(modelReply));
        return new InsightWording(runner, config, MAPPER);
    }

    private static List<Map<String, Object>> cards() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", "Speed up Pacific Crest Apartments 2A");
        m.put("detail", ORIGINAL);
        return new ArrayList<>(List.of(m));
    }

    @Test
    @DisplayName("a shorter wording that keeps every number replaces the sentence and is marked as AI")
    void accepted() throws Exception {
        List<Map<String, Object>> items = cards();
        wording(true, "{\"items\":[{\"id\":0,\"detail\":\"Empty for 24 days, losing about $107 a day. Check the price and photos.\"}]}").rewrite(items);
        assertThat(items.get(0).get("detail")).isEqualTo("Empty for 24 days, losing about $107 a day. Check the price and photos.");
        assertThat(items.get(0).get("wording")).isEqualTo("AI");
    }

    @Test
    @DisplayName("a wording that drops or changes a number is refused and the rules' sentence stays")
    void numberChanged() throws Exception {
        List<Map<String, Object>> items = cards();
        wording(true, "{\"items\":[{\"id\":0,\"detail\":\"Empty for about a month, losing $110 a day.\"}]}").rewrite(items);
        assertThat(items.get(0).get("detail")).isEqualTo(ORIGINAL);
        assertThat(items.get(0)).doesNotContainKey("wording");
    }

    @Test
    @DisplayName("no model, a switched-off tool or a failed call leaves the cards as they were")
    void fallbacks() throws Exception {
        List<Map<String, Object>> offline = cards();
        wording(true, null).rewrite(offline);
        assertThat(offline.get(0).get("detail")).isEqualTo(ORIGINAL);

        Mockito.clearInvocations(runner);
        List<Map<String, Object>> off = cards();
        wording(false, "{\"items\":[{\"id\":0,\"detail\":\"Empty 24 days, $107 a day.\"}]}").rewrite(off);
        assertThat(off.get(0).get("detail")).isEqualTo(ORIGINAL);
        Mockito.verify(runner, Mockito.never()).ask(any(), any(), any());
    }

    @Test
    @DisplayName("a card already reworded is not sent to the model again")
    void cached() throws Exception {
        InsightWording w = wording(true, "{\"items\":[{\"id\":0,\"detail\":\"Empty 24 days, losing $107 a day. Check price and photos.\"}]}");
        w.rewrite(cards());
        w.rewrite(cards());
        Mockito.verify(runner, Mockito.times(1)).ask(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("only the top cards are sent to the model")
    void onlyTheTop() throws Exception {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 10; i++) many.add(new LinkedHashMap<>(Map.of("title", "T" + i, "detail", "Detail " + i + " costs " + (100 + i))));
        wording(true, "{\"items\":[]}").rewrite(many);
        org.mockito.ArgumentCaptor<String> sent = org.mockito.ArgumentCaptor.forClass(String.class);
        Mockito.verify(runner).ask(anyString(), anyString(), sent.capture());
        assertThat(sent.getValue()).contains("Detail 5").doesNotContain("Detail 6");
    }

    @Test
    @DisplayName("the check wants the same numbers, ignores commas and refuses long or unchanged text")
    void check() {
        assertThat(InsightWording.acceptable("Owed $21,103 by 5 residents.", "5 residents owe $21103.")).isTrue();
        assertThat(InsightWording.acceptable("Owed $21,103 by 5 residents.", "Residents owe $21,103.")).isFalse();
        assertThat(InsightWording.acceptable("Owed $21,103 by 5 residents.", "Owed $21,103 by 5 residents.")).isFalse();
        assertThat(InsightWording.acceptable("Owed $21,103.", "x".repeat(300) + " $21,103")).isFalse();
    }
}
