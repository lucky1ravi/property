package com.propertyos.identity;

import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What the PropertyOS operator may and may not do, and how plan features are enforced for companies. */
class PlatformGovernanceIT extends IdentityIntegrationTest {

    private record Company(UUID orgId, String adminToken, String adminEmail) { }

    private Company onboard() {
        String platform = api.login(PLATFORM_ADMIN, DEMO_PASSWORD);
        String email = "admin-" + UUID.randomUUID() + "@governance-test.example.com";
        Reply r = api.post(platform, "/api/identity/platform/orgs",
                Map.of("name", "Gov Co " + UUID.randomUUID().toString().substring(0, 6), "state", "CA", "plan", "GROWTH", "adminName", "Gov Admin", "adminEmail", email));
        assertThat(r.status()).as("onboarding: %s", r.body()).isEqualTo(200);
        return new Company(UUID.fromString(r.body().path("orgId").asText()), api.firstSignIn(email, r.body().path("temporaryPassword").asText(), "Chosen-password-2026"), email);
    }

    @Test
    @DisplayName("the platform admin cannot open a company's business data")
    void operatorIsKeptOutOfCompanyData() {
        String platform = api.login(PLATFORM_ADMIN, DEMO_PASSWORD);
        for (String path : new String[]{"/api/leasing/leases", "/api/billing/invoices", "/api/maintenance/work-orders", "/api/documents/files"}) {
            Reply r = api.get(platform, path);
            assertThat(r.status()).as(path).isEqualTo(403);
            assertThat(r.code()).as(path).isEqualTo("PLATFORM_NO_COMPANY_DATA");
        }
        assertThat(api.get(platform, "/api/identity/platform/orgs").status()).as("but it can manage companies").isEqualTo(200);
    }

    @Test
    @DisplayName("a feature turned off by the platform is refused for that company only")
    void featureToggleIsEnforced() {
        Company company = onboard();
        String platform = api.login(PLATFORM_ADMIN, DEMO_PASSWORD);

        Reply off = api.put(platform, "/api/identity/platform/orgs/" + company.orgId() + "/features", Map.of("MAINTENANCE", false));
        assertThat(off.status()).as("toggle: %s", off.body()).isEqualTo(200);

        Reply blocked = api.get(company.adminToken(), "/api/maintenance/work-orders");
        assertThat(blocked.status()).isEqualTo(403);
        assertThat(blocked.code()).isEqualTo("FEATURE_OFF");

        String summit = api.login(SUMMIT_ADMIN, DEMO_PASSWORD);
        Reply unaffected = api.get(summit, "/api/maintenance/work-orders");
        assertThat(unaffected.code()).as("other companies are not affected").isNotEqualTo("FEATURE_OFF");
    }

    @Test
    @DisplayName("a company admin cannot re-enable a feature the platform turned off")
    void companyCannotChangeEntitlements() {
        Company company = onboard();
        Reply r = api.put(company.adminToken(), "/api/identity/platform/orgs/" + company.orgId() + "/features", Map.of("MAINTENANCE", true));
        assertThat(r.status()).isEqualTo(403);
    }

    @Test
    @DisplayName("a suspended company cannot sign in, and can again once reactivated")
    void suspension() {
        Company company = onboard();
        String platform = api.login(PLATFORM_ADMIN, DEMO_PASSWORD);

        assertThat(api.put(platform, "/api/identity/platform/orgs/" + company.orgId(), Map.of("status", "SUSPENDED")).status()).isEqualTo(200);
        Reply denied = api.post(null, "/api/identity/public/login", Map.of("email", company.adminEmail(), "password", "Chosen-password-2026"));
        assertThat(denied.status()).isEqualTo(403);

        assertThat(api.put(platform, "/api/identity/platform/orgs/" + company.orgId(), Map.of("status", "ACTIVE")).status()).isEqualTo(200);
        assertThat(api.login(company.adminEmail(), "Chosen-password-2026")).isNotBlank();
    }

    @Test
    @DisplayName("sign-in rejects a wrong password with a friendly message")
    void wrongPassword() {
        Reply r = api.post(null, "/api/identity/public/login", Map.of("email", SUMMIT_ADMIN, "password", "wrong"));
        assertThat(r.status()).isEqualTo(401);
        assertThat(r.body().path("detail").asText()).isEqualTo("Incorrect email or password.");
    }

    @Test
    @DisplayName("endpoints reject calls without a token")
    void authRequired() {
        assertThat(api.get(null, "/api/identity/roles").status()).isEqualTo(401);
        assertThat(api.get("not-a-token", "/api/identity/roles").status()).isEqualTo(401);
    }
}
