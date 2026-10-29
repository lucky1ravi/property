package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.ai.llm.Llm;
import com.propertyos.ai.llm.LlmProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.*;

/**
 * Checks an uploaded photo before it is published. Returns a verdict (SAFE, UNSAFE or UNKNOWN), how sure the check is
 * (0 to 1), a category, labels and a plain-language reason.
 * <p>
 * With a vision model configured (see LlmProvider) the model looks at the picture. Without one, a built-in simulator is
 * used so the review flow can be demonstrated: it reads the file name and a rough skin-tone measure. The simulator is
 * clearly labelled in every result and is not a content-safety control for production.
 * If the model fails, the result is UNKNOWN with zero confidence so the image goes to a person, never straight to public.
 */
@Service
public class ImageModeration {
    private static final Logger log = LoggerFactory.getLogger(ImageModeration.class);
    private final LlmProvider provider;
    private final ObjectMapper mapper;

    public ImageModeration(LlmProvider provider, ObjectMapper mapper) {
        this.provider = provider;
        this.mapper = mapper;
    }

    private static final Set<String> CATEGORIES = Set.of("PROPERTY", "PERSON_PHOTO", "EXPLICIT", "VIOLENCE", "HATE", "NOT_PROPERTY", "OTHER");

    public JsonNode check(String kind, String fileName, String mime, byte[] image) {
        boolean profile = "PROFILE".equalsIgnoreCase(kind);
        Optional<Llm> model = provider.vision();
        if (model.isPresent()) {
            try {
                String reply = model.get().vision(system(profile), prompt(profile), image, mime);
                JsonNode out = mapper.readTree(strip(reply));
                return normalise(out, model.get().provider() + ":" + model.get().model(), false);
            } catch (Exception e) {
                log.warn("Image check failed, sending to a person: {}", e.getMessage());
                return result("UNKNOWN", 0, "OTHER", List.of("check-failed"), "The automatic check could not be completed, so a person needs to look.", model.get().provider() + ":" + model.get().model(), false);
            }
        }
        return simulate(profile, fileName == null ? "" : fileName.toLowerCase(), image);
    }

    private static String system(boolean profile) {
        return "You are a content-safety reviewer for a US property management website. You see one uploaded image. Reply with JSON only: "
                + "{\"verdict\": \"SAFE\" or \"UNSAFE\", \"confidence\": number from 0 to 1 for how sure you are of that verdict, "
                + "\"category\": one of PROPERTY, PERSON_PHOTO, EXPLICIT, VIOLENCE, HATE, NOT_PROPERTY, OTHER, \"labels\": up to 5 short strings describing what you see, \"reason\": one plain sentence, "
                + "\"quality\": integer 0 to 100 for how good the photo is for a rental listing (sharp, well lit, straight, uncluttered), \"caption\": a factual caption under 12 words, "
                + "\"qualityIssues\": up to 3 short strings such as blurry, dark, tilted, cluttered}. "
                + (profile
                ? "The image is meant to be a profile photo. SAFE means a normal portrait or avatar with no sexual, violent or hateful content. Mark UNSAFE for nudity, sexual content, gore, weapons pointed at people, hate symbols or anything not suitable for a business profile."
                : "The image is meant to be a photo of a rental property, unit, amenity, floor plan or neighbourhood. SAFE means a clean, appropriate photo that shows real estate. Mark UNSAFE for nudity or sexual content, gore or violence, hate symbols, or images that clearly are not about property (selfies, memes, food, pets, screenshots, vehicles, random objects).")
                + " Never describe real people by name. If unsure, lower the confidence.";
    }

    private static String prompt(boolean profile) {
        return profile ? "Review this profile photo." : "Review this property photo.";
    }

    private static String strip(String s) {
        String t = s == null ? "" : s.trim();
        int a = t.indexOf('{'), b = t.lastIndexOf('}');
        return a >= 0 && b > a ? t.substring(a, b + 1) : t;
    }

    private JsonNode normalise(JsonNode in, String provider, boolean simulated) {
        String verdict = in.path("verdict").asText("UNKNOWN").toUpperCase();
        if (!verdict.equals("SAFE") && !verdict.equals("UNSAFE")) verdict = "UNKNOWN";
        double c = in.path("confidence").asDouble(0);
        if (c > 1) c = c / 100.0;
        c = Math.max(0, Math.min(1, c));
        String cat = in.path("category").asText("OTHER").toUpperCase();
        if (!CATEGORIES.contains(cat)) cat = "OTHER";
        List<String> labels = new ArrayList<>();
        in.path("labels").forEach(l -> { if (labels.size() < 5 && !l.asText().isBlank()) labels.add(l.asText()); });
        String reason = in.path("reason").asText("");
        if (verdict.equals("UNKNOWN")) c = 0;
        ObjectNode r = (ObjectNode) result(verdict, c, cat, labels, reason.isBlank() ? "No reason given." : reason, provider, simulated);
        if (in.path("quality").isNumber()) r.put("quality", Math.max(0, Math.min(100, in.path("quality").asInt())));
        if (in.path("caption").isTextual()) r.put("caption", in.path("caption").asText().length() > 140 ? in.path("caption").asText().substring(0, 140) : in.path("caption").asText());
        ArrayNode qi = r.putArray("qualityIssues");
        in.path("qualityIssues").forEach(x -> { if (qi.size() < 3 && !x.asText().isBlank()) qi.add(x.asText()); });
        return r;
    }

    private JsonNode result(String verdict, double confidence, String category, List<String> labels, String reason, String provider, boolean simulated) {
        ObjectNode o = mapper.createObjectNode();
        o.put("verdict", verdict);
        o.put("confidence", Math.round(confidence * 1000) / 1000.0);
        o.put("category", category);
        ArrayNode l = o.putArray("labels");
        labels.forEach(l::add);
        o.put("reason", reason);
        o.put("provider", provider);
        o.put("simulated", simulated);
        return o;
    }

    // ---- Built-in simulator (demo only)
    private static final List<String> EXPLICIT = List.of("nsfw", "nude", "naked", "sexy", "porn", "xxx", "lingerie", "adult", "erotic", "topless", "explicit");
    private static final List<String> VIOLENT = List.of("gore", "blood", "weapon", "gun", "corpse", "violence", "fight");
    private static final List<String> OFF_TOPIC = List.of("meme", "selfie", "pizza", "burger", "food", "dog", "cat", "pet", "car", "screenshot", "memes", "party", "beach-babe");
    private static final List<String> PROPERTY_HINTS = List.of("kitchen", "bedroom", "bath", "living", "exterior", "front", "lobby", "pool", "gym", "garage", "patio", "balcony", "yard", "roof", "hall", "unit", "apt", "floor", "plan", "building", "house", "dining", "closet", "laundry", "view", "porch", "garden");

    private JsonNode simulate(boolean profile, String name, byte[] image) {
        String who = "built-in simulator (no image model configured)";
        for (String k : EXPLICIT)
            if (name.contains(k)) return result("UNSAFE", 0.97, "EXPLICIT", List.of("adult content suspected", "file name: " + k), "The file name suggests adult content.", who, true);
        for (String k : VIOLENT)
            if (name.contains(k)) return result("UNSAFE", 0.93, "VIOLENCE", List.of("violent content suspected", "file name: " + k), "The file name suggests violent content.", who, true);
        if (!profile)
            for (String k : OFF_TOPIC)
                if (name.contains(k)) return result("UNSAFE", 0.88, "NOT_PROPERTY", List.of("not a property photo", "file name: " + k), "This does not look like a property photo.", who, true);
        BufferedImage img = null;
        try {
            img = ImageIO.read(new ByteArrayInputStream(image));
        } catch (Exception ignored) {
        }
        if (img == null) {
            return result("SAFE", 0.62, profile ? "PERSON_PHOTO" : "PROPERTY", List.of("image could not be analysed"), "The picture format could not be analysed here, so a person should confirm it.", who, true);
        }
        double skin = skinRatio(img);
        if (skin > 0.55 && !profile)
            return result("UNSAFE", 0.6, "OTHER", List.of("large skin-tone area", "needs a person's eyes"), "A large part of the image is skin-toned. This is not typical for a property photo.", who, true);
        if (skin > 0.72)
            return result("UNSAFE", 0.6, "OTHER", List.of("large skin-tone area"), "Most of the image is skin-toned. A person should check it.", who, true);
        boolean hint = PROPERTY_HINTS.stream().anyMatch(name::contains);
        int w = img.getWidth(), h = img.getHeight();
        if (Math.min(w, h) < 200) return result("SAFE", 0.66, profile ? "PERSON_PHOTO" : "PROPERTY", List.of("small image " + w + "x" + h), "The image is very small, so the check is less certain.", who, true);
        if (profile) return result("SAFE", 0.92, "PERSON_PHOTO", List.of("no unsafe content found"), "No unsafe content was found.", who, true);
        return result("SAFE", hint ? 0.96 : 0.86, "PROPERTY", hint ? List.of("property photo", "file name looks like a room or exterior") : List.of("no unsafe content found"),
                hint ? "File name and picture look like a property photo." : "No unsafe content found; could not confirm what room or area it shows.", who, true);
    }

    private static double skinRatio(BufferedImage img) {
        int stepX = Math.max(1, img.getWidth() / 60), stepY = Math.max(1, img.getHeight() / 60);
        int skin = 0, total = 0;
        for (int y = 0; y < img.getHeight(); y += stepY)
            for (int x = 0; x < img.getWidth(); x += stepX) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff, g = (rgb >> 8) & 0xff, b = rgb & 0xff;
                total++;
                if (r > 95 && g > 40 && b > 20 && r > g && r > b && Math.abs(r - g) > 15 && (Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b))) > 15) skin++;
            }
        return total == 0 ? 0 : skin / (double) total;
    }
}
