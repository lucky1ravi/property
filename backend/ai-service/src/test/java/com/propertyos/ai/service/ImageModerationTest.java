package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/** Photo checks: the built-in simulator's rules, and how a real model's answer is cleaned up or replaced when it fails. */
class ImageModerationTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmProvider provider = Mockito.mock(LlmProvider.class);
    private final ImageModeration moderation = new ImageModeration(provider, mapper);

    private static byte[] image(int w, int h, Color c) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        var g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private JsonNode offline(String kind, String name, byte[] bytes) {
        when(provider.vision()).thenReturn(Optional.empty());
        return moderation.check(kind, name, "image/png", bytes);
    }

    @Test
    void simulatorFlagsRiskyFileNames() throws Exception {
        byte[] ok = image(400, 300, Color.GRAY);
        assertThat(offline("PROPERTY", "NSFW-1.png", ok).path("category").asText()).isEqualTo("EXPLICIT");
        assertThat(offline("PROPERTY", "gore.png", ok).path("category").asText()).isEqualTo("VIOLENCE");
        assertThat(offline("PROPERTY", "my-pizza.png", ok).path("category").asText()).isEqualTo("NOT_PROPERTY");
        assertThat(offline("PROFILE", "my-pizza.png", ok).path("verdict").asText()).isEqualTo("SAFE");
        assertThat(offline("PROPERTY", null, ok).path("simulated").asBoolean()).isTrue();
    }

    @Test
    void simulatorLooksAtThePicture() throws Exception {
        assertThat(offline("PROPERTY", "x.png", new byte[]{1, 2, 3}).path("confidence").asDouble()).isEqualTo(0.62);
        Color skin = new Color(200, 140, 110);
        assertThat(offline("PROPERTY", "x.png", image(300, 300, skin)).path("verdict").asText()).isEqualTo("UNSAFE");
        assertThat(offline("PROFILE", "x.png", image(300, 300, skin)).path("verdict").asText()).isEqualTo("UNSAFE");
        assertThat(offline("PROFILE", "x.png", image(300, 300, new Color(230, 150, 100))).path("verdict").asText()).isNotBlank();
        assertThat(offline("PROPERTY", "x.png", image(100, 100, Color.GRAY)).path("reason").asText()).contains("small");
        assertThat(offline("PROFILE", "me.png", image(400, 400, Color.BLUE)).path("category").asText()).isEqualTo("PERSON_PHOTO");
        assertThat(offline("PROPERTY", "kitchen.png", image(400, 400, Color.GRAY)).path("confidence").asDouble()).isEqualTo(0.96);
        assertThat(offline("PROPERTY", "x.png", image(400, 400, Color.GRAY)).path("confidence").asDouble()).isEqualTo(0.86);
    }

    @Test
    void aModelAnswerIsCleanedUp() throws Exception {
        Llm model = Mockito.mock(Llm.class);
        when(model.provider()).thenReturn("test");
        when(model.model()).thenReturn("m");
        when(provider.vision()).thenReturn(Optional.of(model));
        when(model.vision(anyString(), anyString(), any(), anyString())).thenReturn(
                "Sure! {\"verdict\":\"safe\",\"confidence\":87,\"category\":\"kitchen\",\"labels\":[\"a\",\"\",\"b\",\"c\",\"d\",\"e\",\"f\"],"
                        + "\"quality\":150,\"caption\":\"" + "x".repeat(200) + "\",\"qualityIssues\":[\"blurry\",\"dark\",\"tilted\",\"cluttered\",\"\"]} thanks");
        JsonNode r = moderation.check("PROPERTY", "a.png", "image/png", new byte[]{1});
        assertThat(r.path("verdict").asText()).isEqualTo("SAFE");
        assertThat(r.path("confidence").asDouble()).isEqualTo(0.87);
        assertThat(r.path("category").asText()).isEqualTo("OTHER");
        assertThat(r.path("labels")).hasSize(5);
        assertThat(r.path("quality").asInt()).isEqualTo(100);
        assertThat(r.path("caption").asText()).hasSize(140);
        assertThat(r.path("qualityIssues")).hasSize(3);

        when(model.vision(anyString(), anyString(), any(), anyString())).thenReturn("{\"verdict\":\"maybe\",\"confidence\":0.9}");
        JsonNode unknown = moderation.check("PROFILE", "a.png", "image/png", new byte[]{1});
        assertThat(unknown.path("verdict").asText()).isEqualTo("UNKNOWN");
        assertThat(unknown.path("confidence").asDouble()).isZero();
        assertThat(unknown.path("reason").asText()).isEqualTo("No reason given.");
    }

    @Test
    void aFailedModelCallGoesToAPerson() throws Exception {
        Llm model = Mockito.mock(Llm.class);
        when(model.provider()).thenReturn("test");
        when(model.model()).thenReturn("m");
        when(provider.vision()).thenReturn(Optional.of(model));
        when(model.vision(anyString(), anyString(), any(), anyString())).thenThrow(new IllegalStateException("down"));
        JsonNode r = moderation.check("PROPERTY", "a.png", "image/png", new byte[]{1});
        assertThat(r.path("verdict").asText()).isEqualTo("UNKNOWN");
        assertThat(r.path("labels").get(0).asText()).isEqualTo("check-failed");
    }
}
