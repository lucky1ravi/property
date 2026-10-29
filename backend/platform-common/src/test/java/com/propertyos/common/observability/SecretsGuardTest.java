package com.propertyos.common.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Production must not start with development secrets; a demo only warns. */
class SecretsGuardTest {
    private static final String STRONG = "a-genuinely-random-secret-value-0123456789";

    @Test
    void productionRefusesDefaults() {
        SecretsGuard guard = new SecretsGuard(false, "dev-only-jwt-secret-change-me-0123456789abcdef", "dev-internal-service-token");
        assertThat(guard.weakSecrets()).containsExactly("JWT_SECRET", "INTERNAL_TOKEN");
        assertThatThrownBy(guard::verify).isInstanceOf(IllegalStateException.class).hasMessageContaining("JWT_SECRET").hasMessageContaining("INTERNAL_TOKEN");
    }

    @Test
    void shortSecretsAreWeak() {
        assertThat(new SecretsGuard(false, "short", STRONG).weakSecrets()).containsExactly("JWT_SECRET");
        assertThat(new SecretsGuard(false, STRONG, "tiny").weakSecrets()).containsExactly("INTERNAL_TOKEN");
    }

    @Test
    void strongSecretsAndDemoModeStart() {
        assertThatCode(new SecretsGuard(false, STRONG, STRONG)::verify).doesNotThrowAnyException();
        assertThatCode(new SecretsGuard(true, "dev-only-x", "dev-y")::verify).doesNotThrowAnyException();
    }
}
