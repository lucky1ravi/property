package com.propertyos.common.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Issues and validates the platform's signed access tokens (HS256). */
@Component
public class JwtService {
    private final SecretKey key;
    private final String issuer = "propertyos";

    public JwtService(@Value("${propertyos.jwt.secret}") String secret) {
        byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) throw new IllegalStateException("JWT_SECRET must be at least 32 characters");
        this.key = Keys.hmacShaKeyFor(bytes);
    }

    /**
     * Creates a signed access token for a user.
     *
     * @param ctx the user's identity, roles, permissions and limits
     * @param ttl how long the token stays valid
     * @return the compact token
     */
    public String issue(UserContext ctx, Duration ttl) {
        Instant now = Instant.now();
        Map<String, Object> limits = new HashMap<>();
        if (ctx.limits() != null) ctx.limits().forEach((k, v) -> limits.put(k, v.toPlainString()));
        return Jwts.builder()
                .issuer(issuer)
                .subject(ctx.userId().toString())
                .claim("org", ctx.orgId() == null ? null : ctx.orgId().toString())
                .claim("name", ctx.name())
                .claim("email", ctx.email())
                .claim("roles", ctx.roles())
                .claim("perms", new ArrayList<>(ctx.permissions()))
                .claim("scope", ctx.scope())
                .claim("party", ctx.partyId() == null ? null : ctx.partyId().toString())
                .claim("props", ctx.propertyIds() == null ? List.of() : ctx.propertyIds().stream().map(UUID::toString).toList())
                .claim("limits", limits)
                .claim("fields", ctx.fieldRules() == null ? Map.of() : ctx.fieldRules())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key)
                .compact();
    }

    /**
     * Verifies a token's signature, issuer and expiry and rebuilds the user's identity from it.
     *
     * @param token the compact token
     * @return the user context
     * @throws io.jsonwebtoken.JwtException when the token is invalid or expired
     */
    @SuppressWarnings("unchecked")
    public UserContext parse(String token) {
        Claims c = Jwts.parser().verifyWith(key).requireIssuer(issuer).build().parseSignedClaims(token).getPayload();
        String org = c.get("org", String.class);
        String party = c.get("party", String.class);
        List<String> props = (List<String>) c.getOrDefault("props", List.of());
        Map<String, Object> rawLimits = (Map<String, Object>) c.getOrDefault("limits", Map.of());
        Map<String, BigDecimal> limits = new HashMap<>();
        rawLimits.forEach((k, v) -> limits.put(k, new BigDecimal(String.valueOf(v))));
        return new UserContext(
                UUID.fromString(c.getSubject()),
                org == null ? null : UUID.fromString(org),
                c.get("name", String.class),
                c.get("email", String.class),
                (List<String>) c.getOrDefault("roles", List.of()),
                new HashSet<>((List<String>) c.getOrDefault("perms", List.of())),
                c.get("scope", String.class),
                party == null ? null : UUID.fromString(party),
                props.stream().map(UUID::fromString).toList(),
                limits,
                (Map<String, String>) c.getOrDefault("fields", Map.of()),
                false);
    }
}
