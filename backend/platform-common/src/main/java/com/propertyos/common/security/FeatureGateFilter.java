package com.propertyos.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.config.ConfigClient;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Map;

/**
 * Enforces the features a company has been given. When PropertyOS (or the company's plan) turns a feature off, its
 * screens disappear and every call to that module is refused here too, so hiding a menu item is never the only control.
 * Platform operators and service-to-service calls are not gated.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class FeatureGateFilter extends OncePerRequestFilter {
    private static final Map<String, String> MODULES = Map.of(
            "/api/leasing/", "LEASING", "/api/billing/", "PAYMENTS", "/api/maintenance/", "MAINTENANCE",
            "/api/documents/", "DOCUMENTS", "/api/ai/", "AI_ASSISTANT", "/api/workflow/rules", "AUTOMATIONS");
    private static final Map<String, String> LABELS = Map.of(
            "LEASING", "Leasing", "PAYMENTS", "Payments", "MAINTENANCE", "Maintenance", "DOCUMENTS", "Files and documents", "AI_ASSISTANT", "The AI assistant", "AUTOMATIONS", "Automations");
    private final ConfigClient config;
    private final ObjectMapper mapper;

    public FeatureGateFilter(ConfigClient config, ObjectMapper mapper) {
        this.config = config;
        this.mapper = mapper;
    }

    /** The only APIs a PropertyOS operator may call: accounts, companies, configuration, audit, their own alerts. Never a company's business data. */
    private static boolean operatorPath(String p) {
        return p.startsWith("/api/identity/") || p.startsWith("/api/workflow/notifications") || p.equals("/api/ai/status");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String path = req.getRequestURI();
        UserContext ctx = UserContextHolder.get();
        if (ctx != null && !ctx.system() && UserContext.SCOPE_PLATFORM.equals(ctx.scope()) && path.startsWith("/api/") && !path.contains("/public/") && !operatorPath(path)) {
            res.setStatus(403);
            res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
            mapper.writeValue(res.getOutputStream(), Map.of("status", 403, "title", "Forbidden", "code", "PLATFORM_NO_COMPANY_DATA",
                    "detail", "Platform administrators manage companies, plans and features. They can't open a company's leases, payments, files or work orders."));
            return;
        }
        if (ctx != null && !ctx.system() && ctx.orgId() != null && !UserContext.SCOPE_PLATFORM.equals(ctx.scope()) && path.startsWith("/api/") && !path.contains("/public/")) {
            for (Map.Entry<String, String> m : MODULES.entrySet()) {
                if (path.startsWith(m.getKey()) && !config.flag("features/" + m.getValue(), true)) {
                    res.setStatus(403);
                    res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                    mapper.writeValue(res.getOutputStream(), Map.of("status", 403, "title", "Forbidden", "code", "FEATURE_OFF",
                            "detail", LABELS.get(m.getValue()) + " isn't included in your company's PropertyOS plan. Contact your administrator to turn it on."));
                    return;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
