package com.propertyos.testsupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

/**
 * A tiny JSON client for integration tests. Unlike {@code TestRestTemplate} it never throws on 4xx/5xx,
 * so a test can assert on the error status and the problem-details body directly.
 */
public final class ApiClient {
    /** Status and parsed JSON body of one response. */
    public record Reply(int status, JsonNode body) {
        public String code() { return body == null ? null : body.path("code").asText(null); }
    }

    private final String baseUrl;
    private final RestTemplate http = new RestTemplate(new org.springframework.http.client.JdkClientHttpRequestFactory());
    private final ObjectMapper mapper = new ObjectMapper();

    public ApiClient(int port) {
        this.baseUrl = "http://localhost:" + port;
    }

    /**
     * Sends a GET request.
     *
     * @param token bearer token, or null for none
     * @param path  path starting with {@code /}
     * @return status and body (4xx and 5xx bodies are returned, not thrown)
     */
    public Reply get(String token, String path) { return send(HttpMethod.GET, token, path, null); }

    /**
     * Sends a POST request with a JSON body.
     *
     * @param token bearer token, or null for none
     * @param path  path starting with {@code /}
     * @param body  object to send as JSON, or null
     * @return status and body
     */
    public Reply post(String token, String path, Object body) { return send(HttpMethod.POST, token, path, body); }

    /**
     * Sends a PUT request with a JSON body.
     *
     * @param token bearer token, or null for none
     * @param path  path starting with {@code /}
     * @param body  object to send as JSON, or null
     * @return status and body
     */
    public Reply put(String token, String path, Object body) { return send(HttpMethod.PUT, token, path, body); }

    /**
     * Sends a DELETE request.
     *
     * @param token bearer token, or null for none
     * @param path  path starting with {@code /}
     * @return status and body
     */
    public Reply delete(String token, String path) { return send(HttpMethod.DELETE, token, path, null); }

    /** Signs in with email and password and returns the access token. */
    public String login(String email, String password) {
        Reply r = post(null, "/api/identity/public/login", Map.of("email", email, "password", password));
        if (r.status() != 200) throw new AssertionError("Sign-in failed for " + email + ": " + r.status() + " " + r.body());
        return r.body().path("token").asText();
    }

    /**
     * Completes a first sign-in: swaps the temporary password for a new one and returns the access token.
     *
     * @param email             the new user's email
     * @param temporaryPassword the one-time password given at onboarding
     * @param newPassword       the password to set (at least 10 characters)
     * @return the access token
     */
    public String firstSignIn(String email, String temporaryPassword, String newPassword) {
        Reply r = post(null, "/api/identity/public/change-password",
                Map.of("email", email, "currentPassword", temporaryPassword, "newPassword", newPassword));
        if (r.status() != 200) throw new AssertionError("Password change failed for " + email + ": " + r.status() + " " + r.body());
        return r.body().path("token").asText();
    }

    /** Uploads one file as multipart form data. */
    public Reply upload(String token, String path, String fileName, byte[] content, Map<String, String> fields) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return fileName; }
        });
        fields.forEach(form::add);
        HttpHeaders h = headers(token);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        return exchange(HttpMethod.POST, path, new HttpEntity<>(form, h));
    }

    /** Raw status code of a GET, for binary or HTML responses. */
    public int status(String token, String path) {
        return exchange(HttpMethod.GET, path, new HttpEntity<>(headers(token))).status();
    }

    /** Status, headers and raw text of one response, for tests that look at headers rather than JSON. */
    public record Raw(int status, HttpHeaders headers, String text) {
    }

    /**
     * Sends a GET with extra request headers and returns the response headers too.
     *
     * @param token   bearer token, or null for none
     * @param path    path starting with {@code /}
     * @param headers extra request headers
     */
    public Raw getRaw(String token, String path, Map<String, String> headers) {
        HttpHeaders h = headers(token);
        headers.forEach(h::set);
        try {
            ResponseEntity<String> r = http.exchange(baseUrl + path, HttpMethod.GET, new HttpEntity<>(h), String.class);
            return new Raw(r.getStatusCode().value(), r.getHeaders(), r.getBody());
        } catch (HttpStatusCodeException e) {
            return new Raw(e.getStatusCode().value(), e.getResponseHeaders() == null ? new HttpHeaders() : e.getResponseHeaders(), e.getResponseBodyAsString());
        }
    }

    private Reply send(HttpMethod method, String token, String path, Object body) {
        HttpHeaders h = headers(token);
        if (body != null) h.setContentType(MediaType.APPLICATION_JSON);
        return exchange(method, path, new HttpEntity<>(body, h));
    }

    private HttpHeaders headers(String token) {
        HttpHeaders h = new HttpHeaders();
        if (token != null) h.setBearerAuth(token);
        return h;
    }

    private Reply exchange(HttpMethod method, String path, HttpEntity<?> entity) {
        try {
            ResponseEntity<String> r = http.exchange(baseUrl + path, method, entity, String.class);
            return new Reply(r.getStatusCode().value(), parse(r.getBody()));
        } catch (HttpStatusCodeException e) {
            return new Reply(e.getStatusCode().value(), parse(e.getResponseBodyAsString()));
        }
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) return null;
        try {
            return mapper.readTree(text);
        } catch (Exception e) {
            return mapper.valueToTree(Map.of("raw", text));
        }
    }
}
