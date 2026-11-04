package com.propertyos.leasing.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Signing and keys for a campaign's inbound address. The sender computes HMAC-SHA256 of the exact request body with the campaign's secret and sends it
 * in the {@code X-Signature} header, as hex, optionally prefixed {@code sha256=}. Comparison is constant-time. Keys come from a secure random source.
 */
final class CampaignSecurity {
    private CampaignSecurity() {
    }

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String TRACKING_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"; // no look-alike characters

    /** The signature a sender should send for this body: lower-case hex of HMAC-SHA256. */
    static String sign(String secret, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is not available", e);
        }
    }

    /** True when the header carries the right signature for this body. A missing, malformed or wrong signature is simply false. */
    static boolean verify(String secret, String header, byte[] body) {
        if (secret == null || header == null) return false;
        String given = header.trim();
        if (given.regionMatches(true, 0, "sha256=", 0, 7)) given = given.substring(7);
        if (given.length() != 64) return false;
        byte[] expected = sign(secret, body).getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(expected, given.toLowerCase().getBytes(StandardCharsets.US_ASCII));
    }

    /** A new signing secret: 32 random bytes, URL-safe text. */
    static String newSecret() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return "whsec_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** A new public key for the inbound address. It names the campaign; it is not a secret. */
    static String newPublicKey() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return "cmp_" + HexFormat.of().formatHex(b);
    }

    /** A short tracking code for the website link. It names the campaign; it is not a secret. */
    static String newTrackingCode() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 8; i++) s.append(TRACKING_ALPHABET.charAt(RANDOM.nextInt(TRACKING_ALPHABET.length())));
        return s.toString();
    }
}
