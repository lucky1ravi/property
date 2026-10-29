package com.propertyos.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.observability.CorrelationFilter;
import com.propertyos.common.resilience.Resilience;
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
 * <p>
 * Every call forwards the caller's correlation id and runs under {@link Resilience}: a bulkhead, a circuit breaker per target
 * service and, for reads, retries. When a service is down the call fails fast with a
 * {@link com.propertyos.common.resilience.ServiceUnavailableException} instead of tying up threads.
 */
@Component
public class ServiceClient {
    public enum Service {IDENTITY, LEASING, BILLING, MAINTENANCE, WORKFLOW, AI, DOCUMENTS}

    private final Environment env;
    private final String internalToken;
    private final RestClient fast;
    private final RestClient slow;
    private final Resilience resilience;

    public ServiceClient(Environment env, @Value("${propertyos.internal-token}") String internalToken, Resilience resilience) {
        this.env = env;
        this.resilience = resilience;
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

    /**
     * Returns the base URL configured for a service.
     */
    public String baseUrl(Service s) {
        String key = "propertyos.services." + s.name().toLowerCase();
        return env.getProperty(key, "http://localhost:8080");
    }

    private RestClient client(Service s) {
        return s == Service.AI ? slow : fast;
    }

    private String correlation() {
        String id = CorrelationFilter.current();
        return id == null ? java.util.UUID.randomUUID().toString() : id;
    }

    private String org() {
        UserContext ctx = UserContextHolder.get();
        UUID org = ctx == null ? null : ctx.orgId();
        return org == null ? "" : org.toString();
    }

    /**
     * Calls another service with GET, passing the internal token and the current company.
     *
     * @param s    the target service
     * @param path the {@code /internal/...} path
     * @return the JSON response
     */
    public JsonNode get(Service s, String path) {
        return resilience.call(s.name(), true, () -> client(s).get().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .header(CorrelationFilter.HEADER, correlation())
                .retrieve().body(JsonNode.class));
    }

    /**
     * Calls another service with POST, passing the internal token and the current company.
     *
     * @param s    the target service
     * @param path the {@code /internal/...} path
     * @param body the JSON request body
     * @return the JSON response
     */
    public JsonNode post(Service s, String path, Object body) {
        return resilience.call(s.name(), false, () -> client(s).post().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .header(CorrelationFilter.HEADER, correlation())
                .body(body == null ? "{}" : body)
                .retrieve().body(JsonNode.class));
    }

    /** POST forwarding the end user's own token, so the receiver applies their permissions and RLS. */
    public JsonNode postAsUser(Service s, String path, Object body, String bearer) {
        return resilience.call(s.name(), false, () -> client(s).post().uri(baseUrl(s) + path)
                .header(AuthFilter.INTERNAL_HEADER, internalToken)
                .header(AuthFilter.ORG_HEADER, org())
                .header(CorrelationFilter.HEADER, correlation())
                .header("Authorization", bearer)
                .body(body == null ? "{}" : body)
                .retrieve().body(JsonNode.class));
    }
}
