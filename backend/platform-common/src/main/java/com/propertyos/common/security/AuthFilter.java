package com.propertyos.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;

/**
 * Zero-trust request authentication for every service. The gateway validates tokens too,
 * but each service validates again so nothing behind the gateway is trusted blindly.
 * <ul>
 *   <li>/internal/** requires the shared service token (service-to-service calls)</li>
 *   <li>/api/** /public/** is open (published listings, applications)</li>
 *   <li>other /api/** requires a valid user JWT</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class AuthFilter extends OncePerRequestFilter {
    public static final String INTERNAL_HEADER = "X-Internal-Token";
    public static final String ORG_HEADER = "X-Org-Id";

    private final JwtService jwt;
    private final byte[] internalToken;
    private final ObjectMapper mapper;

    public AuthFilter(JwtService jwt, @Value("${propertyos.internal-token}") String internalToken, ObjectMapper mapper) {
        this.jwt = jwt;
        this.internalToken = internalToken.getBytes(StandardCharsets.UTF_8);
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getRequestURI();
        String correlation = req.getHeader("X-Correlation-Id");
        MDC.put("correlationId", correlation == null ? UUID.randomUUID().toString() : correlation);
        try {
            if ("OPTIONS".equalsIgnoreCase(req.getMethod()) || path.startsWith("/actuator")) {
                chain.doFilter(req, res);
                return;
            }
            if (path.startsWith("/internal/")) {
                String token = req.getHeader(INTERNAL_HEADER);
                if (token == null || !MessageDigest.isEqual(internalToken, token.getBytes(StandardCharsets.UTF_8))) {
                    reject(res, 401, "Invalid service credentials");
                    return;
                }
                String org = req.getHeader(ORG_HEADER);
                UserContext ctx = authFromBearer(req);
                if (ctx == null) ctx = UserContext.system(org == null || org.isBlank() ? null : UUID.fromString(org));
                UserContextHolder.set(ctx);
                chain.doFilter(req, res);
                return;
            }
            if (path.contains("/public/")) {
                UserContext ctx = authFromBearer(req);
                if (ctx != null) UserContextHolder.set(ctx);
                chain.doFilter(req, res);
                return;
            }
            if (path.startsWith("/api/")) {
                UserContext ctx;
                try {
                    ctx = authFromBearer(req);
                } catch (Exception e) {
                    reject(res, 401, "Session expired or invalid. Please sign in again.");
                    return;
                }
                if (ctx == null) {
                    reject(res, 401, "Sign-in required");
                    return;
                }
                UserContextHolder.set(ctx);
                MDC.put("userId", String.valueOf(ctx.userId()));
            }
            chain.doFilter(req, res);
        } finally {
            UserContextHolder.clear();
            MDC.clear();
        }
    }

    private UserContext authFromBearer(HttpServletRequest req) {
        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) return null;
        return jwt.parse(header.substring(7));
    }

    private void reject(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        mapper.writeValue(res.getOutputStream(), Map.of("status", status, "title", "Unauthorized", "detail", message));
    }
}
