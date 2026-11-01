package com.propertyos.common.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Short, unguessable tokens that let someone without an account do one narrow thing, for example an applicant
 * uploading files to their own application. Signed with the shared service secret, so any service can verify
 * a token another service issued without a database lookup.
 */
@Component
public class SignedLinks {
    private final byte[] secret;
    private final long ttlDays;
    private final Clock clock;

    @Autowired
    public SignedLinks(@Value("${propertyos.internal-token}") String secret, @Value("${propertyos.links.ttl-days:14}") long ttlDays) {
        this(secret, ttlDays, Clock.systemUTC());
    }

    public SignedLinks(String secret) {
        this(secret, 14, Clock.systemUTC());
    }

    SignedLinks(String secret, long ttlDays, Clock clock) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttlDays = ttlDays;
        this.clock = clock;
    }

    /**
     * Creates a tamper-proof token for a one-purpose link. The link stops working after the configured number of days
     * (14 unless {@code propertyos.links.ttl-days} says otherwise), so a link that was forwarded or left in an inbox does not stay open forever.
     *
     * @param purpose what the link allows, for example {@code application-upload}
     * @param id      the record it applies to
     * @return the token to put in the link
     */
    public String sign(String purpose, UUID id) {
        return sign(purpose, id, clock.instant().plus(Duration.ofDays(ttlDays)));
    }

    /** Like {@link #sign(String, UUID)} with an explicit moment after which the link no longer works. */
    public String sign(String purpose, UUID id, Instant expires) {
        long exp = expires.getEpochSecond();
        return Long.toString(exp, 36) + "." + mac(purpose, id, exp);
    }

    private String mac(String purpose, UUID id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((purpose + ":" + id + ":" + exp).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign link", e);
        }
    }

    /**
     * Checks a link token: it must be ours, for this purpose and record, and not past its expiry.
     *
     * @param purpose what the link is supposed to allow
     * @param id      the record it is supposed to apply to
     * @param token   the token from the link
     * @return true only when the token matches and has not expired
     */
    public boolean verify(String purpose, UUID id, String token) {
        return check(purpose, id, token) == Check.OK;
    }

    /** Why a token is not accepted, so the page can say "this link has expired" instead of just "invalid". */
    public enum Check { OK, EXPIRED, INVALID }

    public Check check(String purpose, UUID id, String token) {
        if (token == null || id == null) return Check.INVALID;
        int dot = token.indexOf('.');
        if (dot < 1) return Check.INVALID;
        long exp;
        try {
            exp = Long.parseLong(token.substring(0, dot), 36);
        } catch (NumberFormatException e) {
            return Check.INVALID;
        }
        boolean same = MessageDigest.isEqual(mac(purpose, id, exp).getBytes(StandardCharsets.UTF_8), token.substring(dot + 1).getBytes(StandardCharsets.UTF_8));
        if (!same) return Check.INVALID;
        return clock.instant().getEpochSecond() > exp ? Check.EXPIRED : Check.OK;
    }
}
