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
    /**
     * Scopes from widest to narrowest. A user with several roles gets the widest.
     */
    private static final List<String> SCOPE_RANK = List.of("PLATFORM", "ORG", "PROPERTY", "OWNER", "TENANT", "VENDOR");
    /**
     * Field visibility from most to least open. A user with several roles gets the most open.
     */
    private static final List<String> FIELD_RANK = List.of("VISIBLE", "MASKED", "HIDDEN");
    /**
     * How long a sign-in token stays valid.
     */
    private static final Duration TTL = Duration.ofHours(12);

    private final IdentityStore store;
    private final JwtService jwt;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final TransactionTemplate tx;
    private final boolean demoMode;
    private final String demoPassword;
    private final SecureRandom random = new SecureRandom();

    public AuthService(IdentityStore store, JwtService jwt, ObjectMapper mapper, AuditService audit,
                       PlatformTransactionManager tm, @Value("${propertyos.demo.enabled:true}") boolean demoMode,
                       @Value("${propertyos.demo.password:PropertyOS@2026}") String demoPassword) {
        this.demoPassword = demoPassword;
        this.store = store;
        this.jwt = jwt;
        this.mapper = mapper;
        this.audit = audit;
        this.tx = new TransactionTemplate(tm);
        this.demoMode = demoMode;
    }

    /**
     * Tells whether demo sign-in is enabled.
     */
    public boolean demoMode() {
        return demoMode;
    }

    /** Builds the caller's full security context from the database (runs as system). */
    public UserContext buildContext(UUID userId) {
        return UserContextHolder.runAsSystem(null, () -> tx.execute(s -> {
            Map<String, Object> u = store.user(userId).orElseThrow(() -> ApiException.unauthorized("Unknown user"));
            if (!"ACTIVE".equals(u.get("status"))) throw ApiException.forbidden("This account is disabled");
            store.org((UUID) u.get("org_id")).ifPresent(o -> {
                if (!"ACTIVE".equals(o.get("status"))) throw ApiException.forbidden(com.propertyos.common.security.AuthFilter.SUSPENDED_MESSAGE);
            });
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
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return Map.of();
        }
    }

    /**
     * Builds the user's context from their roles and permissions, signs a token, and records the sign-in in the audit trail.
     *
     * @param userId the user
     * @param method how they signed in, recorded for audit
     * @return the token and its lifetime in seconds
     */
    public Map<String, Object> issue(UUID userId, String method) {
        UserContext ctx = buildContext(userId);
        String token = jwt.issue(ctx, TTL);
        UserContextHolder.runAs(ctx, () -> tx.executeWithoutResult(s -> {
            store.touchLogin(userId);
            audit.record("User", userId, "SIGNED_IN", null, null, Map.of("method", method));
        }));
        return Map.of("token", token, "expiresInSeconds", TTL.toSeconds());
    }

    /**
     * Signs in a demo persona.
     *
     * @param userId the persona's user id
     * @throws ApiException (403) when demo mode is off
     */
    public Map<String, Object> demoLogin(UUID userId) {
        if (!demoMode) throw ApiException.forbidden("Demo sign-in is disabled");
        return issue(userId, "DEMO_PERSONA");
    }

    /** Email and password sign-in. Demo accounts without a stored password accept the shared demo password. */
    public Map<String, Object> login(String email, String password) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        Map<String, Object> user = UserContextHolder.runAsSystem(null, () -> tx.execute(s -> store.userByEmail(normalized))).orElse(null);
        boolean ok = false;
        if (user != null && password != null && !password.isEmpty()) {
            Object stored = user.get("password_hash");
            if (stored != null) ok = verifyPassword(password, stored.toString());
            else ok = demoMode && MessageDigest.isEqual(password.getBytes(StandardCharsets.UTF_8), demoPassword.getBytes(StandardCharsets.UTF_8));
        }
        if (!ok) throw ApiException.unauthorized("Incorrect email or password.");
        if (Boolean.TRUE.equals(user.get("must_change_password")))
            throw new ApiException(org.springframework.http.HttpStatus.FORBIDDEN, PASSWORD_CHANGE_REQUIRED, "You're signing in with a temporary password. Choose your own to continue.");
        return issue((UUID) user.get("id"), "PASSWORD");
    }

    /** Error code the sign-in page looks for, to show the "choose a password" step. */
    public static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";

    /**
     * Replaces a password (most often a temporary one) after checking the current one, then signs the user in.
     * The new password needs at least 10 characters and must differ from the current one.
     *
     * @param email           the user's email
     * @param currentPassword the password they were given or use now
     * @param newPassword     the password they choose
     * @return the sign-in token
     * @throws ApiException (401) when the email or current password is wrong, (400) when the new password is not acceptable
     */
    public Map<String, Object> changePassword(String email, String currentPassword, String newPassword) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        Map<String, Object> user = UserContextHolder.runAsSystem(null, () -> tx.execute(s -> store.userByEmail(normalized))).orElse(null);
        boolean ok = false;
        if (user != null && currentPassword != null && !currentPassword.isEmpty()) {
            Object stored = user.get("password_hash");
            if (stored != null) ok = verifyPassword(currentPassword, stored.toString());
            else ok = demoMode && MessageDigest.isEqual(currentPassword.getBytes(StandardCharsets.UTF_8), demoPassword.getBytes(StandardCharsets.UTF_8));
        }
        if (!ok) throw ApiException.unauthorized("Incorrect email or password.");
        if (newPassword == null || newPassword.length() < 10) throw ApiException.badRequest("Your new password must be at least 10 characters.");
        if (newPassword.equals(currentPassword)) throw ApiException.badRequest("Choose a password that is different from the temporary one.");
        if (newPassword.equals(demoPassword)) throw ApiException.badRequest("That password is publicly known. Choose another.");
        UUID id = (UUID) user.get("id");
        UserContextHolder.runAsSystem(null, () -> {
            tx.executeWithoutResult(s -> store.setPassword(id, hashPassword(newPassword)));
            return null;
        });
        UserContext ctx = buildContext(id);
        UserContextHolder.runAs(ctx, () -> tx.executeWithoutResult(s -> audit.record("User", id, "PASSWORD_CHANGED", null, null, Map.of())));
        return issue(id, "PASSWORD_CHANGED");
    }

    private static final String PASSWORD_ALPHABET_LOWER = "abcdefghjkmnpqrstuvwxyz";
    private static final String PASSWORD_ALPHABET_UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String PASSWORD_ALPHABET_DIGITS = "23456789";
    private static final String PASSWORD_ALPHABET_SYMBOLS = "-_.!#";

    /**
     * Makes a random temporary password of 14 characters: letters and digits that are easy to read aloud (no 0/O, 1/l/I),
     * with at least one of each kind and one symbol.
     */
    public static String temporaryPassword() {
        SecureRandom random = new SecureRandom();
        String all = PASSWORD_ALPHABET_LOWER + PASSWORD_ALPHABET_UPPER + PASSWORD_ALPHABET_DIGITS + PASSWORD_ALPHABET_SYMBOLS;
        List<Character> chars = new ArrayList<>();
        for (String set : List.of(PASSWORD_ALPHABET_LOWER, PASSWORD_ALPHABET_UPPER, PASSWORD_ALPHABET_DIGITS, PASSWORD_ALPHABET_SYMBOLS)) chars.add(set.charAt(random.nextInt(set.length())));
        while (chars.size() < 14) chars.add(all.charAt(random.nextInt(all.length())));
        Collections.shuffle(chars, random);
        StringBuilder sb = new StringBuilder();
        chars.forEach(sb::append);
        return sb.toString();
    }

    /**
     * Returns the shared password accepted for demo users.
     */
    public String demoPassword() {
        return demoPassword;
    }

    /** PBKDF2-HMAC-SHA256, 120k iterations, random salt. Format: pbkdf2$iterations$salt$hash (base64). */
    public static String hashPassword(String password) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] h = pbkdf2(password, salt, 120_000);
            return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt) + "$" + Base64.getEncoder().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static boolean verifyPassword(String password, String stored) {
        try {
            String[] p = stored.split("\\$");
            byte[] h = pbkdf2(password, Base64.getDecoder().decode(p[2]), Integer.parseInt(p[1]));
            return MessageDigest.isEqual(h, Base64.getDecoder().decode(p[3]));
        } catch (Exception e) { // a malformed stored hash, or an unavailable algorithm, never matches
            return false;
        }
    }

    private static byte[] pbkdf2(String password, byte[] salt, int iterations) throws Exception {
        var spec = new javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        return javax.crypto.SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    }

    /** Sends a one-time sign-in code. In demo mode the code is returned so reviewers can sign in. */
    public Map<String, Object> requestOtp(String email) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        // A missing or malformed address is a mistake in the request, not an account lookup, so say so (unknown but valid addresses still get the same reply as known ones).
        if (!normalized.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) throw ApiException.badRequest("Enter a valid email address.");
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

    /**
     * Checks a one-time code (limited attempts, expires after 10 minutes) and signs the user in.
     *
     * @param email the address the code was sent to
     * @param code  the code entered
     * @return token and user summary
     * @throws ApiException (401) when the code is wrong or expired
     */
    public Map<String, Object> verifyOtp(String email, String code) {
        String normalized = email == null ? "" : email.trim().toLowerCase();
        // Decide inside the transaction but throw outside it: an exception would roll back the attempt counter,
        // which would let someone guess codes without ever being locked out.
        Object outcome = UserContextHolder.runAsSystem(null, () -> tx.execute(s -> {
            Optional<Map<String, Object>> found = store.otp(normalized);
            if (found.isEmpty()) return "EXPIRED";
            Map<String, Object> otp = found.get();
            if (((Number) otp.get("attempts")).intValue() >= 5) {
                store.deleteOtp(normalized);
                return "LOCKED";
            }
            if (!MessageDigest.isEqual(hash(code).getBytes(StandardCharsets.UTF_8), ((String) otp.get("code_hash")).getBytes(StandardCharsets.UTF_8))) {
                store.bumpOtpAttempts(normalized);
                return "WRONG";
            }
            store.deleteOtp(normalized);
            return (UUID) store.userByEmail(normalized).orElseThrow().get("id");
        }));
        if ("EXPIRED".equals(outcome)) throw ApiException.unauthorized("Code expired. Request a new one.");
        if ("LOCKED".equals(outcome)) throw ApiException.unauthorized("Too many attempts. Request a new code.");
        if ("WRONG".equals(outcome)) throw ApiException.unauthorized("Incorrect code.");
        UUID userId = (UUID) outcome;
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
