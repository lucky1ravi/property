package com.propertyos.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SignedLinksTest {
    private final SignedLinks links = new SignedLinks("unit-test-internal-token");
    private final UUID id = UUID.randomUUID();

    @Test
    @DisplayName("a signed link verifies for the same purpose and record")
    void valid() {
        assertThat(links.verify("application-upload", id, links.sign("application-upload", id))).isTrue();
    }

    @Test
    @DisplayName("a link is bound to its purpose and its record")
    void bound() {
        String token = links.sign("application-upload", id);
        assertThat(links.verify("other-purpose", id, token)).isFalse();
        assertThat(links.verify("application-upload", UUID.randomUUID(), token)).isFalse();
    }

    @Test
    @DisplayName("missing or forged tokens are rejected")
    void invalid() {
        assertThat(links.verify("application-upload", id, null)).isFalse();
        assertThat(links.verify("application-upload", null, "x")).isFalse();
        assertThat(links.verify("application-upload", id, "forged")).isFalse();
    }

    @Test
    @DisplayName("another secret produces links that do not verify")
    void otherSecret() {
        String token = new SignedLinks("a-different-secret").sign("application-upload", id);
        assertThat(links.verify("application-upload", id, token)).isFalse();
    }

    @Test
    @DisplayName("a link stops working after its lifetime and says so")
    void expires() {
        java.time.Instant now = java.time.Instant.parse("2026-01-01T00:00:00Z");
        SignedLinks early = new SignedLinks("unit-test-internal-token", 14, java.time.Clock.fixed(now, java.time.ZoneOffset.UTC));
        String token = early.sign("application-upload", id);
        assertThat(early.check("application-upload", id, token)).isEqualTo(SignedLinks.Check.OK);
        SignedLinks thirteenDays = new SignedLinks("unit-test-internal-token", 14, java.time.Clock.fixed(now.plus(java.time.Duration.ofDays(13)), java.time.ZoneOffset.UTC));
        assertThat(thirteenDays.verify("application-upload", id, token)).isTrue();
        SignedLinks fifteenDays = new SignedLinks("unit-test-internal-token", 14, java.time.Clock.fixed(now.plus(java.time.Duration.ofDays(15)), java.time.ZoneOffset.UTC));
        assertThat(fifteenDays.check("application-upload", id, token)).isEqualTo(SignedLinks.Check.EXPIRED);
        assertThat(fifteenDays.verify("application-upload", id, token)).isFalse();
    }

    @Test
    @DisplayName("the expiry cannot be extended by editing the link")
    void expiryIsSigned() {
        String token = links.sign("application-upload", id, java.time.Instant.now().minusSeconds(60));
        assertThat(links.check("application-upload", id, token)).isEqualTo(SignedLinks.Check.EXPIRED);
        String forged = Long.toString(java.time.Instant.now().plusSeconds(86400).getEpochSecond(), 36) + token.substring(token.indexOf('.'));
        assertThat(links.check("application-upload", id, forged)).isEqualTo(SignedLinks.Check.INVALID);
        assertThat(links.check("application-upload", id, "nodot")).isEqualTo(SignedLinks.Check.INVALID);
        assertThat(links.check("application-upload", id, "!!.abc")).isEqualTo(SignedLinks.Check.INVALID);
        assertThat(links.check("application-upload", id, ".abc")).isEqualTo(SignedLinks.Check.INVALID);
    }
}
