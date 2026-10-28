package com.propertyos.common.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Enforces each organisation's configured workflow. Definitions are edited in the
 * workflow designer (workflow-service) and cached here for 15 seconds; if the workflow
 * service is unreachable, the built-in template is used so operations never stop.
 */
@Component
public class WorkflowEngine {
    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final long TTL_MS = 15_000;

    private final ServiceClient client;
    private final DefaultWorkflows defaults;
    private final ObjectMapper mapper;
    private final Map<String, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(WorkflowDefinition def, long at) {
    }

    public WorkflowEngine(ServiceClient client, DefaultWorkflows defaults, ObjectMapper mapper) {
        this.client = client;
        this.defaults = defaults;
        this.mapper = mapper;
    }

    public WorkflowDefinition definition(String key) {
        UUID org = UserContextHolder.get() == null ? null : UserContextHolder.get().orgId();
        String cacheKey = org + ":" + key;
        Cached c = cache.get(cacheKey);
        if (c != null && System.currentTimeMillis() - c.at < TTL_MS) return c.def;
        try {
            JsonNode n = client.get(ServiceClient.Service.WORKFLOW, "/internal/workflows/" + key);
            WorkflowDefinition d = mapper.treeToValue(n, WorkflowDefinition.class);
            cache.put(cacheKey, new Cached(d, System.currentTimeMillis()));
            return d;
        } catch (Exception e) {
            log.debug("Using built-in workflow {}: {}", key, e.getMessage());
            return c != null ? c.def : defaults.get(key);
        }
    }

    /**
     * Validates that the current user may perform this action from the current state.
     *
     * @param amount money involved, checked against the user's approval limit (may be null)
     */
    public WorkflowDefinition.Transition authorize(String key, String currentState, String action, String reason, BigDecimal amount) {
        WorkflowDefinition def = definition(key);
        UserContext ctx = UserContextHolder.require();
        WorkflowDefinition.Transition t = def.transitions().stream()
                .filter(x -> x.action().equals(action) && x.from().contains(currentState))
                .findFirst()
                .orElseThrow(() -> ApiException.conflict("This step isn't allowed while the record is " + def.label(currentState).toLowerCase() + "."));
        if (t.system() && !ctx.system()) throw ApiException.forbidden("This step is performed automatically by the system.");
        if (t.permission() != null && !ctx.has(t.permission()))
            throw ApiException.forbidden("Your role can't perform '" + t.label() + "' (needs " + t.permission() + ").");
        if (t.requiresReason() && (reason == null || reason.isBlank()))
            throw ApiException.badRequest("A reason is required for '" + t.label() + "'.");
        if (t.limitKey() != null && amount != null) {
            BigDecimal limit = ctx.limit(t.limitKey());
            if (limit != null && amount.compareTo(limit) > 0)
                throw ApiException.forbidden("Amount " + usd(amount) + " exceeds your approval limit of " + usd(limit) + ".");
        }
        return t;
    }

    /** Actions the current user can take from this state, for rendering buttons. */
    public List<Map<String, Object>> availableActions(String key, String state) {
        WorkflowDefinition def = definition(key);
        UserContext ctx = UserContextHolder.get();
        return def.transitions().stream()
                .filter(t -> t.from().contains(state) && !t.system())
                .filter(t -> ctx != null && (t.permission() == null || ctx.has(t.permission())))
                .map(t -> Map.<String, Object>of("action", t.action(), "label", t.label(), "to", t.to(),
                        "requiresReason", t.requiresReason()))
                .toList();
    }

    public Integer slaHours(String key, String state) {
        return definition(key).state(state).map(WorkflowDefinition.State::slaHours).orElse(null);
    }

    private static String usd(BigDecimal v) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(v);
    }
}
