package com.propertyos.common.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserContextTest {
    private static UserContext with(String scope, Set<String> permissions) {
        return new UserContext(UUID.randomUUID(), UUID.randomUUID(), "Test", "t@example.com", List.of(), permissions, scope, null, List.of(), Map.of(), Map.of(), false);
    }

    @Test
    @DisplayName("an exact permission is allowed and an unrelated one is not")
    void exact() {
        UserContext u = with(UserContext.SCOPE_ORG, Set.of("LEASE:VIEW"));
        assertThat(u.has("LEASE:VIEW")).isTrue();
        assertThat(u.has("LEASE:APPROVE")).isFalse();
        assertThat(u.has("INVOICE:VIEW")).isFalse();
    }

    @Test
    @DisplayName("FEATURE:* grants every action on that feature only")
    void featureWildcard() {
        UserContext u = with(UserContext.SCOPE_ORG, Set.of("LEASE:*"));
        assertThat(u.has("LEASE:APPROVE")).isTrue();
        assertThat(u.has("INVOICE:VIEW")).isFalse();
    }

    @Test
    @DisplayName("*:* grants everything")
    void superWildcard() {
        assertThat(with(UserContext.SCOPE_ORG, Set.of("*:*")).has("ANYTHING:AT_ALL")).isTrue();
    }

    @Test
    @DisplayName("platform administrators and internal calls pass permission checks")
    void operators() {
        assertThat(with(UserContext.SCOPE_PLATFORM, Set.of()).has("PLATFORM:CONFIGURE")).isTrue();
        assertThat(UserContext.system(UUID.randomUUID()).has("LEASE:VIEW")).isTrue();
    }

    @Test
    @DisplayName("a user with no permissions can do nothing")
    void none() {
        assertThat(with(UserContext.SCOPE_TENANT, Set.of()).has("LEASE:VIEW")).isFalse();
        assertThat(with(UserContext.SCOPE_TENANT, null).has("LEASE:VIEW")).isFalse();
    }
}
