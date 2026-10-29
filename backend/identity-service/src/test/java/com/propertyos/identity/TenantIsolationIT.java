package com.propertyos.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

/** One company must never see, reuse or change another company's roles and users. */
class TenantIsolationIT extends IdentityIntegrationTest {

    /** Onboards a new company through the platform API and returns its admin's token. */
    private String onboardCompany(String name) {
        String platform = api.login(PLATFORM_ADMIN, DEMO_PASSWORD);
        String adminEmail = "admin-" + UUID.randomUUID() + "@isolation-test.example.com";
        Reply created = api.post(platform, "/api/identity/platform/orgs",
                Map.of("name", name, "state", "TX", "plan", "GROWTH", "adminName", name + " Admin", "adminEmail", adminEmail));
        assertThat(created.status()).as("onboarding: %s", created.body()).isEqualTo(200);
        return api.firstSignIn(adminEmail, created.body().path("temporaryPassword").asText(), "Chosen-password-2026");
    }

    private static boolean contains(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).anyMatch(n -> value.equals(n.path(field).asText()));
    }

    @Test
    @DisplayName("roles created by one company are invisible to another company")
    void rolesAreNotShared() {
        String summit = api.login(SUMMIT_ADMIN, DEMO_PASSWORD);
        String roleName = "Isolation test role " + UUID.randomUUID();
        Reply role = api.post(summit, "/api/identity/roles", Map.of("name", roleName, "scope", "ORG"));
        assertThat(role.status()).as("create role: %s", role.body()).isEqualTo(200);

        assertThat(contains(api.get(summit, "/api/identity/roles").body(), "name", roleName)).as("visible to its own company").isTrue();

        String other = onboardCompany("Other Co " + UUID.randomUUID().toString().substring(0, 6));
        Reply theirRoles = api.get(other, "/api/identity/roles");
        assertThat(theirRoles.status()).isEqualTo(200);
        assertThat(contains(theirRoles.body(), "name", roleName)).as("visible to another company").isFalse();
        assertThat(contains(theirRoles.body(), "name", "Org Admin")).as("has its own default roles").isTrue();
    }

    @Test
    @DisplayName("a company cannot assign another company's role, and its own admin keeps access")
    void foreignRoleCannotBeAssigned() {
        String summit = api.login(SUMMIT_ADMIN, DEMO_PASSWORD);
        String roleName = "Foreign role " + UUID.randomUUID();
        UUID foreignRoleId = UUID.fromString(api.post(summit, "/api/identity/roles", Map.of("name", roleName, "scope", "ORG")).body().path("id").asText());

        String other = onboardCompany("Intruder Co " + UUID.randomUUID().toString().substring(0, 6));
        JsonNode users = api.get(other, "/api/identity/users").body();
        String myUserId = users.get(0).path("id").asText();

        Reply attempt = api.put(other, "/api/identity/users/" + myUserId, Map.of("roleIds", java.util.List.of(foreignRoleId.toString())));
        assertThat(attempt.status()).as("assigning a foreign role: %s", attempt.body()).isBetween(400, 499);

        Reply stillAdmin = api.get(other, "/api/identity/roles");
        assertThat(stillAdmin.status()).as("the admin keeps their own roles").isEqualTo(200);
    }

    @Test
    @DisplayName("one company's users are not listed for another")
    void usersAreNotShared() {
        String other = onboardCompany("Users Co " + UUID.randomUUID().toString().substring(0, 6));
        JsonNode users = api.get(other, "/api/identity/users").body();
        assertThat(contains(users, "email", SUMMIT_ADMIN)).isFalse();
        assertThat(users.size()).isEqualTo(1);
    }
}
