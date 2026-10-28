package com.propertyos.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Calls the other services with the signed-in user's own token. Row-level security and permissions therefore apply
 * to everything the assistant reads or does: it can never see more than the person asking.
 */
@Component
public class Upstream {
    private final ServiceClient services;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public Upstream(ServiceClient services, ObjectMapper mapper) {
        this.services = services;
        this.mapper = mapper;
    }

    public JsonNode get(String bearer, String path) {
        return call(bearer, "GET", path, null);
    }

    public JsonNode post(String bearer, String path, Object body) {
        return call(bearer, "POST", path, body);
    }

    /** Returns null instead of throwing when the user simply isn't allowed (403) or the service is down. */
    public JsonNode tryGet(String bearer, String path) {
        try {
            return get(bearer, path);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonNode call(String bearer, String method, String path, Object body) {
        try {
            String[] seg = path.split("/");   // "", "api", "<service>", ...
            ServiceClient.Service svc = ServiceClient.Service.valueOf(seg[2].toUpperCase());
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(services.baseUrl(svc) + path)).timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + bearer).header("Content-Type", "application/json");
            b = "GET".equals(method) ? b.GET() : b.method(method, HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body == null ? java.util.Map.of() : body)));
            HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode n = res.body() == null || res.body().isBlank() ? mapper.nullNode() : mapper.readTree(res.body());
            if (res.statusCode() / 100 != 2) throw new ApiException(org.springframework.http.HttpStatus.valueOf(res.statusCode()), "UPSTREAM", n.path("detail").asText("Request failed"));
            return n;
        } catch (ApiException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Service unavailable: " + e.getMessage(), e);
        }
    }

    public static String currentBearer() {
        org.springframework.web.context.request.ServletRequestAttributes a =
                (org.springframework.web.context.request.ServletRequestAttributes) org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
        String h = a == null ? null : a.getRequest().getHeader("Authorization");
        if (h == null || !h.startsWith("Bearer ")) throw ApiException.unauthorized("Sign in again to use the assistant.");
        return h.substring(7);
    }

    public static boolean has(String permission) {
        return UserContextHolder.require().has(permission);
    }
}
