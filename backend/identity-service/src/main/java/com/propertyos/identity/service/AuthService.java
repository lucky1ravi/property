package com.propertyos.identity.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.security.JwtService;
import com.propertyos.common.security.UserContext;
import com.propertyos.common.security.UserContextHolder;
import com.propertyos.common.web.ApiException;
import com.propertyos.identity.store.IdentityStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;

/** Sign-in (email one-time code, demo persona sign-in) and token issuing. */
@Service
public class AuthService {
    private static final List<String> SCOPE_RANK = List.of("PLATFORM", "ORG", "PROPERTY", "OWNER", "TENANT", "VENDOR");
    private static final List<String> FIELD_RANK = List.of("VISIBLE", "MASKED", "HIDDEN");
    private static final Duration TTL = Duration.ofHours(12);

    private final IdentityStore store;
    private final JwtService jwt;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final boolean demoMode;
    private final SecureRandom random = new SecureRandom();

    public AuthService(IdentityStore store, JwtService jwt, ObjectMapper mapper, AuditService audit,
                       PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demoMode) {
        this.store = store;
        this.jwt = jwt;
        this.mapper = mapper;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.demoMode = demoMode;
    }

    public boolean demoMode() {
        return demoMode;
    }

    /** Builds the caller's full security context from the database (runs as system). */
    public UserContext buildContext(UUID userId) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(s -> {
            Map<String, Object> u = store.user(userId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
            if (!"ACTIVE".equals(u.get("status"))) throw ApiException.forbidden("This account is disabled");
            List<Map<String, Object>> roles = store.userRoles(userId);
            String scope = roles.stream().map(r -> (String) r.get("scope"))
                    .min(Comparator.comparingInt(SCOPE_RANK::indexOf)).orElse("TENANT");
            Set<String> perms = new HashSet<>();
            Map<String, BigDecimal> limits = new HashMap<>();
            Set<String> unlimited = new HashSet<>();
            for (Map<String, Object> p : store.permissionsForUser(userId)) {
                String key = p.get("feature") + ":" + p.get("action");
                perms.add(key);
                BigDecimal max = (BigDecimal) p.get("max_amount");
                if (max == null) unlimited.add(key);
                else limits.merge(key, max, BigDecimal::max);
            }
            unlimited.forEach(limits::remove);
            Map<String, String> fields = new HashMap<>();
            boolean first = true;
            for (Map<String, Object> r : roles) {
                Map<String, String> rules = parseRules(r.get("field_rules"));
                if (first) {
                    fields.putAll(rules);
                    first = false;
                } else {
                    // Most permissive rule across the user's roles wins; fields absent from a role are visible.
                    Set<String> keys = new HashSet<>(fields.keySet());
                    keys.addAll(rules.keySet());
                    for (String k : keys) {
                        String a = fields.getOrDefault(k, "VISIBLE"), b = rules.getOrDefault(k, "VISIBLE");
                        String best = FIELD_RANK.indexOf(a) <= FIELD_RANK.indexOf(b) ? a : b;
                        if ("VISIBLE".equals(best)) fields.remove(k);
                        else fields.put(k, best);
                    }
                }
            }
            List<UUID> props = "PROPERTY".equals(scope) ? store.userPropertyIds(userId) : List.of();
            return new UserContext(userId, (UUID) u.get("org_id"), (String) u.get("name"), (String) u.get("email"),
                    roles.stream().map(r -> (String) r.get("name")).toList(), perms, scope, (UUID) u.get("party_id"),
                    props, limits, fields, false);
        }));
    }

    @SuppressWarnings("unchecked")
    private Map<String, String> parseRules(Object raw) {
        try {
            if (raw == null) return Map.of();
            return mapper.readValue(raw.toString(), Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }

    public Map<String, Object> issue(UUID userId, String method) {
        UserContext ctx = buildContext(userId);
        String token = jwt.issue(ctx, TTL);
        UserContextHolder.runAs(ctx, () -> tx.executeWithoutResult(s -> {
            store.touchLogin(userId);
            audit.record("User", userId, "SIGNED_IN", null, null, Map.of("method", method));
        }));
        return Map.of("token", token, "expiresInSeconds", TTL.toSeconds());
    }

    public Map<String, Object> demoLogin(UUID userId) {
        if (!demoMode) throw ApiException.forbidden("Demo sign-in is disabled");
        return issue(userId, "DEMO_PERSONA");
    }

    /** Sends a one-time sign-in code. In demo mode the code is returned so reviewers can sign in. */
    public Map<String, Object> requestOtp(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        Optional<Map<String, Object>> user = UserContextHolder.runAsSystem(null, () -> tx.execute(s -> store.userByEmail(normalized)));
        String code = String.format("%06d", random.nextInt(1_000_000));
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("sent", true);
        res.put("message", "If this email has an account, a 6-digit code has been sent. It expires in 10 minutes.");
        if (user.isPresent()) {
            UserContextHolder.runAsSystem(null, () -> {
                tx.executeWithoutResult(s -> store.saveOtp(normalized, hash(code)));
                return null;
            });
            if (demoMode) res.put("demoCode", code);
        }
        return res;
    }

    public Map<String, Object> verifyOtp(String email, String code) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        UUID userId = UserContextHolder.runAsSystem(null, () -> tx.execute(s -> {
            Map<String, Object> otp = store.otp(normalized).orElseThrow(() -> ApiException.unauthorized("Code expired. Request a new one."));
            if (((Number) otp.get("attempts")).intValue() >= 5) {
                store.deleteOtp(normalized);
                throw ApiException.unauthorized("Too many attempts. Request a new code.");
            }
            if (!MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.UTF_8), ((String) otp.get("code_hash")).getBytes(StandardCharsets.UTF_8))) {
                store.bumpOtpAttempts(normalized);
                throw ApiException.unauthorized("Incorrect code.");
            }
            store.deleteOtp(normalized);
            return (UUID) store.userByEmail(normalized).orElseThrow().get("id");
        }));
        return issue(userId, "EMAIL_OTP");
    }

    private static String hash(String code) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(("propertyos-otp:" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
