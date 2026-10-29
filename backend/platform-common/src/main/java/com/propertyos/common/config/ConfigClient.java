package com.propertyos.common.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.security.UserContextHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads an organisation's effective configuration (platform defaults, then plan, then
 * organisation overrides) from the identity service, cached for 15 seconds so UI changes
 * apply within seconds without a redeploy. Falls back to platform defaults if unreachable.
 */
@Component
public class ConfigClient {
    private static final Logger log = LoggerFactory.getLogger(ConfigClient.class);
    /**
     * How long a company's configuration is cached, in milliseconds.
     */
    private static final long TTL_MS = 15_000;
    private final ServiceClient client;
    private final Map<UUID, Cached> cache = new ConcurrentHashMap<>();
    private final JsonNode defaults;

    private record Cached(JsonNode value, long at) {
    }

    public ConfigClient(ServiceClient client, ObjectMapper mapper) throws Exception {
        this.client = client;
        this.defaults = mapper.readTree(new ClassPathResource("config/defaults.json").getInputStream());
    }

    /**
     * Returns the built-in default configuration that applies when a company has changed nothing.
     */
    public JsonNode defaults() {
        return defaults;
    }

    /**
     * Returns the effective configuration for a company: its own settings merged over the defaults.
     *
     * Results are cached briefly; use {@link #invalidate(UUID)} after a change.
     *
     * @param orgId the company, or null for the defaults
     * @return the merged configuration tree
     */
    public JsonNode forOrg(UUID orgId) {
        if (orgId == null) return defaults;
        Cached c = cache.get(orgId);
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS) return c.value;
        try {
            JsonNode v = UserContextHolder.runAsSystem(orgId, () -> client.get(ServiceClient.Service.IDENTITY, "/internal/config/" + orgId));
            cache.put(orgId, new Cached(v, System.currentTimeMillis()));
            return v;
        } catch (Exception e) {
            log.debug("Config fetch failed for {}: {}", orgId, e.getMessage());
            return c != null ? c.value : defaults;
        }
    }

    /**
     * Returns the effective configuration for the signed-in user's company.
     */
    public JsonNode current() {
        return forOrg(UserContextHolder.orgId());
    }

    /** Value at a slash path such as "fees/lateFee/flat", or a missing node. */
    public JsonNode at(String path) {
        path = path.replace('.', '/');   // callers use both "fees.nsfFee" and "fees/nsfFee"
        JsonNode n = current().at("/" + path);
        if (n.isMissingNode()) n = defaults.at("/" + path);
        return n == null ? MissingNode.getInstance() : n;
    }

    /**
     * Reads a numeric setting.
     *
     * @param path     slash-separated path, for example {@code fees/lateFee}
     * @param fallback value used when the setting is missing
     * @return the setting as a decimal
     */
    public BigDecimal number(String path, String fallback) {
        JsonNode n = at(path);
        return n.isMissingNode() || n.isNull() ? new BigDecimal(fallback) : new BigDecimal(n.asText());
    }

    /**
     * Reads a true/false setting.
     *
     * @param path     slash-separated path
     * @param fallback value used when the setting is missing
     * @return the setting
     */
    public boolean flag(String path, boolean fallback) {
        JsonNode n = at(path);
        return n.isMissingNode() || n.isNull() ? fallback : n.asBoolean();
    }

    /**
     * Reads a text setting.
     *
     * @param path     slash-separated path
     * @param fallback value used when the setting is missing
     * @return the setting
     */
    public String text(String path, String fallback) {
        JsonNode n = at(path);
        return n.isMissingNode() || n.isNull() ? fallback : n.asText();
    }

    /** State rule for a US state (deposit cap, late fee cap, grace days, refund days). */
    public JsonNode stateRule(String state) {
        JsonNode n = at("stateRules/" + state);
        return n.isMissingNode() ? at("stateRules/DEFAULT") : n;
    }

    /**
     * Tells whether a product feature is switched on for the signed-in user's company.
     *
     * @param feature the feature key, for example {@code DOCUMENTS}
     * @return true unless the company has turned the feature off
     */
    public boolean featureEnabled(String feature) {
        return flag("features/" + feature, true);
    }

    /**
     * Drops the cached configuration for a company so the next read fetches fresh values.
     *
     * @param orgId the company whose settings changed
     */
    public void invalidate(UUID orgId) {
        cache.remove(orgId);
    }
}
