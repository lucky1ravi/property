package com.propertyos.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.security.AuthFilter;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.UUID;

/**
 * Service-to-service HTTP calls on the internal network. Calls carry the shared service
 * token and the organisation id, so the receiving service runs them as the system for
 * that organisation only.
 */
@Component
public class ServiceClient {
    public enum Service {IDENTITY, LEASING, BILLING, MAINTENANCE, WORKFLOW, AI, DOCUMENTS}

    private final Environment env;
    private final String internalToken;
    private final RestClient fast;
    private final RestClient slow;

    public ServiceClient(Environment env, @Value("${propertyos.internal-token}") String internalToken) {
        this.env = env;
        this.internalToken = internalToken;
        this.fast = RestClient.builder().requestFactory(factory(3000, 8000)).build();
        this.slow = RestClient.builder().requestFactory(factory(3000, 90000)).build();
    }

    private static SimpleClientHttpRequestFactory factory(int connect, int read) {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(connect);
        f.setReadTimeout(read);
        return f;
    }

    public String baseUrl(Service s) {
        String key = "propertyos.services." + s.name().toLowerCase();
        return env.getProperty(key, "http://localhost:8080");
    }

    private RestClient client(Service s) {
        return s == Service.AI ? slow : fast;
    }

    private String org() {
        UserContext ctx = UserContextHolder.get();
        UUID org = ctx == null ? null : ctx.orgId();
        return org == null ? "" : org.toString();
    }

    public JsonNode get(Service s, String path) {
        return client(s).get().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .retrieve().body(JsonNode.class);
    }

    public JsonNode post(Service s, String path, Object body) {
        return client(s).post().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .body(body == null ? "{}" : body)
                .retrieve().body(JsonNode.class);
    }

    /** POST forwarding the end user's own token, so the receiver applies their permissions and RLS. */
    public JsonNode postAsUser(Service s, String path, Object body, String bearer) {
        return client(s).post().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .header("Authorization", bearer)
                .body(body == null ? "{}" : body)
                .retrieve().body(JsonNode.class);
    }
}
