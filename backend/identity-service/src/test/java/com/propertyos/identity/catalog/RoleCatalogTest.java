package com.propertyos.identity.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** The built-in roles must only grant permissions that exist, and the newer features (boosts, review replies, campaigns) must reach the right roles. */
class RoleCatalogTest {
    private static Set<String> grants(String roleKey) {
        return DefaultRoles.byKey(roleKey).orElseThrow().grants().stream().map(g -> g.feature() + ":" + g.action()).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("every permission a built-in role grants is a real permission in the catalog")
    void allValid() {
        for (DefaultRoles.RoleTemplate t : DefaultRoles.TEMPLATES)
            for (DefaultRoles.Grant g : t.grants())
                assertThat(FeatureCatalog.valid(g.feature(), g.action())).as(t.key() + " grants " + g.feature() + ":" + g.action()).isTrue();
    }

    @Test
    @DisplayName("features added after launch are synced to existing companies' built-in roles, and each one exists")
    void synced() {
        for (String f : DefaultRoles.SYNCED_FEATURES)
            assertThat(FeatureCatalog.FEATURES.stream().anyMatch(x -> x.key().equals(f))).as(f).isTrue();
        assertThat(DefaultRoles.SYNCED_FEATURES).contains("BOOST", "REVIEW", "CAMPAIGN");
    }

    @Test
    @DisplayName("buying a boost is the administrator's; managers and brokers can see boosts")
    void boostPermissions() {
        assertThat(grants("ORG_ADMIN")).contains("BOOST:VIEW", "BOOST:CREATE");
        assertThat(grants("PROPERTY_MANAGER")).contains("BOOST:VIEW").doesNotContain("BOOST:CREATE");
        assertThat(grants("BROKER")).contains("BOOST:VIEW").doesNotContain("BOOST:CREATE");
        assertThat(grants("LEASING_AGENT")).noneMatch(p -> p.startsWith("BOOST:"));
        assertThat(grants("TENANT")).noneMatch(p -> p.startsWith("BOOST:"));
        assertThat(grants("OWNER")).noneMatch(p -> p.startsWith("BOOST:"));
    }

    @Test
    @DisplayName("replying to a review is for the leasing team; deciding what is shown is for brokers and above")
    void reviewPermissions() {
        assertThat(grants("ORG_ADMIN")).contains("REVIEW:EDIT", "REVIEW:APPROVE");
        assertThat(grants("PROPERTY_MANAGER")).contains("REVIEW:EDIT", "REVIEW:APPROVE");
        assertThat(grants("BROKER")).contains("REVIEW:EDIT", "REVIEW:APPROVE");
        assertThat(grants("LEASING_AGENT")).contains("REVIEW:EDIT").doesNotContain("REVIEW:APPROVE");
        for (String r : new String[]{"ACCOUNTANT", "OWNER", "TENANT", "VENDOR"}) assertThat(grants(r)).noneMatch(p -> p.startsWith("REVIEW:"));
    }

    @Test
    @DisplayName("campaigns: brokers and managers run them, agents and accountants can look, nobody outside the company can")
    void campaignPermissions() {
        assertThat(grants("ORG_ADMIN")).contains("CAMPAIGN:VIEW", "CAMPAIGN:CREATE", "CAMPAIGN:EDIT");
        assertThat(grants("PROPERTY_MANAGER")).contains("CAMPAIGN:VIEW", "CAMPAIGN:CREATE", "CAMPAIGN:EDIT");
        assertThat(grants("BROKER")).contains("CAMPAIGN:VIEW", "CAMPAIGN:CREATE", "CAMPAIGN:EDIT");
        assertThat(grants("LEASING_AGENT")).contains("CAMPAIGN:VIEW").doesNotContain("CAMPAIGN:CREATE", "CAMPAIGN:EDIT");
        assertThat(grants("ACCOUNTANT")).contains("CAMPAIGN:VIEW").doesNotContain("CAMPAIGN:CREATE", "CAMPAIGN:EDIT");
        for (String r : new String[]{"OWNER", "TENANT", "VENDOR"}) assertThat(grants(r)).noneMatch(p -> p.startsWith("CAMPAIGN:"));
    }

    @Test
    @DisplayName("webhooks send company data to other systems, so only administrators hold the permissions by default")
    void webhookPermissions() {
        assertThat(grants("ORG_ADMIN")).contains("WEBHOOK:VIEW", "WEBHOOK:CREATE", "WEBHOOK:EDIT", "WEBHOOK:DELETE");
        for (String r : new String[]{"PROPERTY_MANAGER", "BROKER", "LEASING_AGENT", "ACCOUNTANT", "OWNER", "TENANT", "VENDOR"})
            assertThat(grants(r)).as(r).noneMatch(p -> p.startsWith("WEBHOOK:"));
        assertThat(DefaultRoles.SYNCED_FEATURES).contains("WEBHOOK");
    }

    @Test
    @DisplayName("the platform operator's role has no company permissions of these kinds")
    void platformRole() {
        Set<String> p = DefaultRoles.PLATFORM_ADMIN.grants().stream().map(g -> g.feature() + ":" + g.action()).collect(Collectors.toSet());
        assertThat(p).noneMatch(x -> x.startsWith("BOOST:") || x.startsWith("REVIEW:") || x.startsWith("CAMPAIGN:"));
    }
}
