package com.propertyos.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request a correlation id and puts it on every log line, so one user action can be followed across the gateway and all services.
 * <p>
 * The id comes from the {@code X-Correlation-Id} header when the caller sent a safe one; otherwise a new one is created.
 * It is returned in the response, kept in the logging context for the request, and forwarded by {@code ServiceClient}.
 * Each finished request is written to the log once, with method, path, status and duration. Health checks are not logged.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class CorrelationFilter extends OncePerRequestFilter {
    /** Header carrying the id between callers and services. */
    public static final String HEADER = "X-Correlation-Id";
    /** Logging context key for the id. */
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9._-]{8,64}");
    private static final Logger access = LoggerFactory.getLogger("access");
    private final String service;

    public CorrelationFilter(@Value("${spring.application.name:service}") String service) {
        this.service = service;
    }

    /**
     * Returns the correlation id of the request being handled, or null outside a request.
     */
    public static String current() {
        return MDC.get(MDC_KEY);
    }

    /**
     * Accepts the caller's id only when it is short and made of safe characters, so it cannot be used to forge log lines.
     */
    static String resolve(String supplied) {
        return supplied != null && SAFE.matcher(supplied).matches() ? supplied : UUID.randomUUID().toString();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException {
        String id = resolve(req.getHeader(HEADER));
        MDC.put(MDC_KEY, id);
        MDC.put("service", service);
        res.setHeader(HEADER, id);
        long start = System.nanoTime();
        try {
            chain.doFilter(req, res);
        } finally {
            if (!req.getRequestURI().startsWith("/actuator"))
                access.info("{} {} -> {} in {} ms", req.getMethod(), req.getRequestURI(), res.getStatus(), (System.nanoTime() - start) / 1_000_000);
            MDC.remove(MDC_KEY);
            MDC.remove("service");
        }
    }
}
