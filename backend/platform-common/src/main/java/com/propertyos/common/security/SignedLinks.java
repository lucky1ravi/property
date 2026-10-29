package com.propertyos.common.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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

    public SignedLinks(@Value("${propertyos.internal-token}") String secret) {
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(String purpose, UUID id) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] h = mac.doFinal((purpose + ":" + id).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h).substring(0, 32);
        } catch (Exception e) {
            throw new IllegalStateException("Could not sign link", e);
        }
    }

    public boolean verify(String purpose, UUID id, String token) {
        if (token == null || id == null) return false;
        return MessageDigest.isEqual(sign(purpose, id).getBytes(StandardCharsets.UTF_8), token.getBytes(StandardCharsets.UTF_8));
    }
}
