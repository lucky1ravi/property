package com.propertyos.common.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {
    private static final String SECRET = "unit-test-secret-that-is-long-enough-0123456789";
    private final JwtService jwt = new JwtService(SECRET);

    private static UserContext user() {
        return new UserContext(UUID.randomUUID(), UUID.randomUUID(), "Sarah Mitchell", "sarah@example.com", List.of("Org Admin"),
                Set.of("LEASE:VIEW", "LEASE:APPROVE"), UserContext.SCOPE_ORG, null, List.of(), Map.of("WORK_ORDER:APPROVE", new BigDecimal("1000")),
                Map.of("Applicant.ssn", "MASKED"), false);
    }

    @Test
    @DisplayName("a token carries the caller's identity, permissions, limits and field rules")
    void roundTrip() {
        UserContext original = user();
        UserContext parsed = jwt.parse(jwt.issue(original, Duration.ofMinutes(5)));

        assertThat(parsed.userId()).isEqualTo(original.userId());
        assertThat(parsed.orgId()).isEqualTo(original.orgId());
        assertThat(parsed.permissions()).containsExactlyInAnyOrder("LEASE:VIEW", "LEASE:APPROVE");
        assertThat(parsed.limit("WORK_ORDER:APPROVE")).isEqualByComparingTo("1000");
        assertThat(parsed.fieldRules()).containsEntry("Applicant.ssn", "MASKED");
        assertThat(parsed.system()).isFalse();
    }

    @Test
    @DisplayName("an expired token is rejected")
    void expired() {
        String token = jwt.issue(user(), Duration.ofSeconds(-5));
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a token signed with another secret is rejected")
    void wrongKey() {
        String token = new JwtService("another-secret-that-is-also-long-enough-987654321").issue(user(), Duration.ofMinutes(5));
        assertThatThrownBy(() -> jwt.parse(token)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a tampered token is rejected")
    void tampered() {
        String token = jwt.issue(user(), Duration.ofMinutes(5));
        String forged = token.substring(0, token.length() - 4) + "AAAA";
        assertThatThrownBy(() -> jwt.parse(forged)).isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("a weak secret stops the service from starting")
    void shortSecret() {
        assertThatThrownBy(() -> new JwtService("too-short")).isInstanceOf(IllegalStateException.class);
    }
}
