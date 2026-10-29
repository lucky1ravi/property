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
}
