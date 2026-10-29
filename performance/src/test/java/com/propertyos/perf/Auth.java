package com.propertyos.perf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Signs in the demo personas once before the test starts, so the measured traffic is the API itself and not the sign-in page.
 * Tokens last 12 hours, which covers even a long soak test.
 */
final class Auth {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newHttpClient();

    private Auth() {
    }

    /**
     * Signs in every persona of the configured company.
     *
     * @return role key (for example {@code PROPERTY_MANAGER}) to bearer token
     */
    static Map<String, String> signInAll() {
        try {
            JsonNode users = get("/api/identity/public/demo-users").path("users");
            Map<String, String> tokens = new LinkedHashMap<>();
            for (JsonNode u : users) {
                if (!u.path("organization").asText().contains(Config.ORG)) continue;
                String body = JSON.writeValueAsString(Map.of("userId", u.path("id").asText()));
                HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(Config.BASE_URL + "/api/identity/public/demo-login"))
                        .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
                if (r.statusCode() == 200) tokens.put(u.path("roleKey").asText(), JSON.readTree(r.body()).path("token").asText());
            }
            if (tokens.isEmpty())
                throw new IllegalStateException("No demo personas could sign in at " + Config.BASE_URL + ". Is the platform running with DEMO_MODE=true and company '" + Config.ORG + "'?");
            return tokens;
        } catch (java.io.IOException | InterruptedException e) {
            throw new IllegalStateException("Cannot reach the platform at " + Config.BASE_URL + ": " + e.getMessage(), e);
        }
    }

    /**
     * Tokens as feeder records ({@code token} column) for one role, repeated round-robin by Gatling.
     */
    static List<Map<String, Object>> feederFor(Map<String, String> tokens, String role) {
        String token = tokens.get(role);
        if (token == null) throw new IllegalStateException("Persona " + role + " is not available in company " + Config.ORG);
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(Map.of("token", token));
        return rows;
    }

    private static JsonNode get(String path) throws java.io.IOException, InterruptedException {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(Config.BASE_URL + path)).GET().build(), HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() != 200) throw new IllegalStateException("GET " + path + " returned " + r.statusCode());
        return JSON.readTree(r.body());
    }
}
