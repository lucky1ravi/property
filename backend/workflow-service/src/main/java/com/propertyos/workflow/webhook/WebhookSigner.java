package com.propertyos.workflow.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signs outgoing webhook deliveries. The signature is the hex HMAC-SHA256, with the endpoint's secret, of {@code <timestamp>.<body>}, sent as
 * {@code X-PropertyOS-Signature: sha256=<hex>} next to {@code X-PropertyOS-Timestamp}. Including the timestamp lets a receiver reject a captured request that
 * is replayed later. Every attempt is signed afresh with the time it is sent.
 */
public final class WebhookSigner {
    private WebhookSigner() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String sign(String secret, long timestampSeconds, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((timestampSeconds + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** The value of the {@code X-PropertyOS-Signature} header. */
    public static String header(String secret, long timestampSeconds, byte[] body) {
        return "sha256=" + sign(secret, timestampSeconds, body);
    }

    /** What a receiver does: true when the header matches (constant-time). Used by tests and the documentation's examples. */
    public static boolean verify(String secret, long timestampSeconds, byte[] body, String header) {
        if (secret == null || header == null) return false;
        String given = header.trim();
        if (given.regionMatches(true, 0, "sha256=", 0, 7)) given = given.substring(7);
        return given.length() == 64 && MessageDigest.isEqual(sign(secret, timestampSeconds, body).getBytes(StandardCharsets.US_ASCII), given.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** A new signing secret: 32 random bytes, URL-safe text. */
    public static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }
}
