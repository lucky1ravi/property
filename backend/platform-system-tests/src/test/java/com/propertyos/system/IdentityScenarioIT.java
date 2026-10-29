package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Identity service end to end: sign-in (demo, password, one-time code), user and role administration,
 * configuration versions, the audit trail and platform administration of companies.
 */
class IdentityScenarioIT {
    private final ApiClient identity = Flow.identity();

    @Test
    void signInMethodsWork() {
        Flow.Soft soft = new Flow.Soft();
        JsonNode users = Flow.ok(identity.get(null, "/api/identity/public/demo-users"), "demo users").body();
        assertTrue(users.path("enabled").asBoolean());
        JsonNode first = users.path("users").get(0);
        Reply login = Flow.ok(identity.post(null, "/api/identity/public/demo-login", Map.of("userId", first.path("id").asText())), "demo login");
        assertFalse(login.body().isEmpty());
        assertEquals(400, identity.post(null, "/api/identity/public/demo-login", Map.of()).status());

        JsonNode options = Flow.ok(identity.get(null, "/api/identity/public/options"), "options").body();
        String password = options.path("demoPassword").asText();
        String email = first.path("email").asText();
        soft.seen(identity.post(null, "/api/identity/public/login", Map.of("email", email, "password", password)), "password login");
        assertEquals(401, identity.post(null, "/api/identity/public/login", Map.of("email", email, "password", "wrong-password")).status());
        assertEquals(401, identity.post(null, "/api/identity/public/login", Map.of("email", "nobody@nowhere.test", "password", "x")).status());

        Reply otp = Flow.ok(identity.post(null, "/api/identity/public/otp/request", Map.of("email", email)), "otp request");
        String code = otp.body().path("demoCode").asText();
        assertEquals(401, identity.post(null, "/api/identity/public/otp/verify", Map.of("email", email, "code", "000000x")).status());
        soft.seen(identity.post(null, "/api/identity/public/otp/verify", Map.of("email", email, "code", code)), "otp verify");
        Reply stranger = Flow.ok(identity.post(null, "/api/identity/public/otp/request", Map.of("email", "nobody@nowhere.test")), "otp for unknown email");
        assertFalse(stranger.body().has("demoCode"));
        assertEquals(401, identity.post(null, "/api/identity/public/otp/verify", Map.of("email", "nobody@nowhere.test", "code", "123456")).status());

        // Too many wrong guesses lock the code.
        Reply again = identity.post(null, "/api/identity/public/otp/request", Map.of("email", email));
        String fresh = again.body().path("demoCode").asText();
        for (int i = 0; i < 6; i++)
            soft.seen(identity.post(null, "/api/identity/public/otp/verify", Map.of("email", email, "code", "999999")), "wrong code " + i);
        assertEquals(401, identity.post(null, "/api/identity/public/otp/verify", Map.of("email", email, "code", fresh)).status());
        soft.assertClean();
    }

    @Test
    void profileAndRefresh() {
        String admin = Flow.token("ORG_ADMIN");
        JsonNode me = Flow.ok(identity.get(admin, "/api/identity/me"), "me").body();
        assertTrue(me.path("permissions").size() > 10);
        Flow.ok(identity.post(admin, "/api/identity/refresh", Map.of()), "refresh");
        assertEquals(401, identity.get(null, "/api/identity/me").status());
        for (String role : Flow.env().demoRoleKeys()) Flow.ok(identity.get(Flow.token(role), "/api/identity/me"), "me as " + role);
    }

    @Test
    void usersCanBeManaged() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        JsonNode catalog = Flow.ok(identity.get(admin, "/api/identity/catalog"), "catalog").body();
        assertFalse(catalog.isEmpty());
        JsonNode roles = Flow.ok(identity.get(admin, "/api/identity/roles"), "roles").body();
        String roleId = roles.get(0).path("id").asText();
        String email = "new.user." + UUID.randomUUID().toString().substring(0, 8) + "@example.test";

        assertEquals(400, identity.post(admin, "/api/identity/users", Map.of("email", "not-an-email", "name", "Bad")).status());
        assertEquals(400, identity.post(admin, "/api/identity/users", Map.of("name", "No email")).status());
        assertEquals(400, identity.post(admin, "/api/identity/users", Map.of("email", email, "name", "Short", "password", "short")).status());
        Reply created = Flow.ok(identity.post(admin, "/api/identity/users",
                Flow.map("email", email, "name", "New Person", "title", "Analyst", "phone", "555-0100",
                        "password", "a-long-enough-password", "roleIds", List.of(roleId), "propertyIds", List.of())), "create user");
        String id = created.body().path("id").asText();
        soft.seen(identity.post(null, "/api/identity/public/login", Map.of("email", email, "password", "a-long-enough-password")), "new user signs in");
        Flow.ok(identity.get(admin, "/api/identity/users"), "list users");
        Flow.ok(identity.put(admin, "/api/identity/users/" + id, Flow.map("name", "Renamed", "title", "Lead", "phone", "555-0101",
                "status", "ACTIVE", "roleIds", List.of(roleId), "propertyIds", List.of())), "update user");
        Flow.ok(identity.get(admin, "/api/identity/audit/" + id), "user timeline");
        assertEquals(404, identity.put(admin, "/api/identity/users/" + UUID.randomUUID(), Map.of("name", "x")).status());

        String myId = Flow.ok(identity.get(admin, "/api/identity/me"), "me").body().path("userId").asText();
        assertEquals(400, identity.delete(admin, "/api/identity/users/" + myId).status());
        assertEquals(400, identity.put(admin, "/api/identity/users/" + myId, Map.of("roleIds", List.of())).status());
        Flow.ok(identity.delete(admin, "/api/identity/users/" + id), "delete user");
        assertEquals(404, identity.delete(admin, "/api/identity/users/" + id).status());

        assertEquals(403, identity.get(Flow.token("TENANT"), "/api/identity/users").status());
        assertEquals(403, identity.post(Flow.token("TENANT"), "/api/identity/users", Map.of("email", email, "name", "x")).status());
        soft.assertClean();
    }

    @Test
    void rolesAndPermissionsCanBeManaged() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        JsonNode roles = Flow.ok(identity.get(admin, "/api/identity/roles"), "roles").body();
        String orgAdminRole = null, systemRole = null;
        for (JsonNode r : roles) {
            if ("ORG_ADMIN".equals(r.path("key").asText())) orgAdminRole = r.path("id").asText();
            if (r.path("system").asBoolean(false) || r.path("systemRole").asBoolean(false) || r.path("system_role").asBoolean(false)) systemRole = r.path("id").asText();
        }
        assertEquals(400, identity.post(admin, "/api/identity/roles", Map.of("name", "Odd", "scope", "GALAXY")).status());
        assertEquals(404, identity.post(admin, "/api/identity/roles", Map.of("name", "Copy", "copyFromRoleId", UUID.randomUUID().toString())).status());
        Reply made = Flow.ok(identity.post(admin, "/api/identity/roles", Flow.map("name", "Test role " + UUID.randomUUID().toString().substring(0, 6),
                "description", "Created by a test", "scope", "ORG", "color", "#112233", "copyFromRoleId", roles.get(0).path("id").asText())), "create role");
        String id = made.body().path("id").asText();
        Flow.ok(identity.put(admin, "/api/identity/roles/" + id, Flow.map("name", "Renamed role", "description", "Edited", "scope", "PROPERTY", "color", "#445566")), "update role");
        assertEquals(400, identity.put(admin, "/api/identity/roles/" + id, Map.of("scope", "GALAXY")).status());
        soft.seen(identity.put(admin, "/api/identity/roles/" + id, Map.of("fieldRules", Map.of("lease", Map.of("rent", "HIDDEN")))), "field rules");
        soft.seen(identity.put(admin, "/api/identity/roles/" + id, Map.of("fieldRules", "not json")), "bad field rules");
        assertEquals(404, identity.put(admin, "/api/identity/roles/" + UUID.randomUUID(), Map.of("name", "x")).status());

        List<Map<String, Object>> grants = new ArrayList<>();
        grants.add(Flow.map("feature", "INVOICE", "action", "VIEW", "maxAmount", null));
        grants.add(Flow.map("feature", "PAYMENT", "action", "APPROVE", "maxAmount", 5000));
        Flow.ok(identity.put(admin, "/api/identity/roles/" + id + "/permissions", grants), "set permissions");
        assertEquals(400, identity.put(admin, "/api/identity/roles/" + id + "/permissions", List.of(Flow.map("feature", "NOPE", "action", "VIEW"))).status());
        assertEquals(403, identity.put(admin, "/api/identity/roles/" + id + "/permissions", List.of(Flow.map("feature", "PLATFORM", "action", "CONFIGURE"))).status());
        assertEquals(404, identity.put(admin, "/api/identity/roles/" + UUID.randomUUID() + "/permissions", List.of()).status());
        if (orgAdminRole != null)
            assertEquals(400, identity.put(admin, "/api/identity/roles/" + orgAdminRole + "/permissions", List.of(Flow.map("feature", "INVOICE", "action", "VIEW"))).status());
        if (systemRole != null) assertEquals(400, identity.delete(admin, "/api/identity/roles/" + systemRole).status());

        Flow.ok(identity.delete(admin, "/api/identity/roles/" + id), "delete role");
        assertEquals(404, identity.delete(admin, "/api/identity/roles/" + id).status());
        assertEquals(403, identity.post(Flow.token("TENANT"), "/api/identity/roles", Map.of("name", "x")).status());
        soft.assertClean();
    }

    @Test
    void configurationIsVersionedAndAudited() {
        Flow.Soft soft = new Flow.Soft();
        String admin = Flow.token("ORG_ADMIN");
        JsonNode cfg = Flow.ok(identity.get(admin, "/api/identity/config"), "config").body();
        Flow.ok(identity.get(admin, "/api/identity/config/overrides"), "overrides");
        int startVersion = cfg.path("version").asInt();
        for (String section : List.of("features", "fees", "invoicing", "approvals", "screening", "sla", "renewals", "ai", "notifications",
                "branding", "stateRules", "payments", "alerts", "chat", "documents", "moderation", "templates", "customFields")) {
            JsonNode current = cfg.path(section);
            if (current.isMissingNode()) continue;
            soft.seen(identity.put(admin, "/api/identity/config/" + section + "?summary=test+change", current), "put " + section);
        }
        assertEquals(400, identity.put(admin, "/api/identity/config/nonsense", Map.of("a", 1)).status());
        ObjectNode features = cfg.path("features").isObject() ? cfg.path("features").deepCopy() : null;
        if (features != null) {
            features.put("NOT_A_FEATURE_" + UUID.randomUUID().toString().substring(0, 4), true);
            soft.seen(identity.put(admin, "/api/identity/config/features", features), "unknown feature");
        }
        JsonNode history = Flow.ok(identity.get(admin, "/api/identity/config/history"), "history").body();
        assertTrue(history.size() >= 1);
        int version = history.get(history.size() - 1).path("version").asInt(startVersion);
        soft.seen(identity.post(admin, "/api/identity/config/rollback/" + version, Map.of()), "rollback");
        assertEquals(404, identity.post(admin, "/api/identity/config/rollback/99999", Map.of()).status());
        Flow.ok(identity.get(admin, "/api/identity/audit?limit=5"), "audit");
        Flow.ok(identity.get(admin, "/api/identity/audit?limit=100000&entityType=User"), "audit by type");
        Flow.ok(identity.get(admin, "/api/identity/audit?limit=-4"), "audit low limit");
        assertEquals(403, identity.get(Flow.token("TENANT"), "/api/identity/audit").status());
        assertEquals(403, identity.put(Flow.token("TENANT"), "/api/identity/config/fees", Map.of()).status());
        assertEquals(403, identity.get(Flow.token("TENANT"), "/api/identity/config/overrides").status());
        soft.assertClean();
    }

    @Test
    void platformAdministratorsManageCompanies() {
        Flow.Soft soft = new Flow.Soft();
        String platform = Flow.token("PLATFORM_ADMIN");
        JsonNode orgs = Flow.ok(identity.get(platform, "/api/identity/platform/orgs"), "orgs").body();
        assertTrue(orgs.size() >= 2);
        Flow.ok(identity.get(platform, "/api/identity/platform/summary"), "summary");

        assertEquals(400, identity.post(platform, "/api/identity/platform/orgs", Map.of("name", "Incomplete")).status());
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        assertEquals(400, identity.post(platform, "/api/identity/platform/orgs", Flow.map("name", "Bad plan " + suffix, "state", "TX",
                "adminName", "Admin", "adminEmail", "admin." + suffix + "@example.test", "plan", "GALACTIC")).status());
        Reply onboarded = Flow.ok(identity.post(platform, "/api/identity/platform/orgs", Flow.map("name", "Test Realty " + suffix, "state", "tx",
                "adminName", "Test Admin", "adminEmail", "admin." + suffix + "@example.test", "plan", "STARTER")), "onboard");
        String orgId = onboarded.body().path("orgId").asText();
        assertEquals(409, identity.post(platform, "/api/identity/platform/orgs", Flow.map("name", "Dup " + suffix, "state", "TX",
                "adminName", "Test Admin", "adminEmail", "admin." + suffix + "@example.test")).status());

        // The new administrator gets a one-time temporary password and must replace it before getting a token.
        String adminEmail = "admin." + suffix + "@example.test";
        String temp = onboarded.body().path("temporaryPassword").asText();
        assertTrue(temp.length() >= 12, "a temporary password is returned once");
        Reply blocked = identity.post(null, "/api/identity/public/login", Map.of("email", adminEmail, "password", temp));
        assertEquals(403, blocked.status());
        assertEquals("PASSWORD_CHANGE_REQUIRED", blocked.body().path("code").asText());
        assertFalse(blocked.body().has("token"), "no token before the password is changed");
        String chosen = "Chosen-" + suffix + "-pw";
        String change = "/api/identity/public/change-password";
        assertEquals(401, identity.post(null, change, Map.of("email", adminEmail, "currentPassword", "wrong-one", "newPassword", chosen)).status());
        assertEquals(400, identity.post(null, change, Map.of("email", adminEmail, "currentPassword", temp, "newPassword", "short")).status());
        assertEquals(400, identity.post(null, change, Map.of("email", adminEmail, "currentPassword", temp, "newPassword", temp)).status());
        Reply changed = Flow.ok(identity.post(null, change, Map.of("email", adminEmail, "currentPassword", temp, "newPassword", chosen)), "change password");
        assertFalse(changed.body().isEmpty());
        Flow.ok(identity.post(null, "/api/identity/public/login", Map.of("email", adminEmail, "password", chosen)), "sign in with the new password");
        assertEquals(401, identity.post(null, "/api/identity/public/login", Map.of("email", adminEmail, "password", temp)).status());

        JsonNode details = Flow.ok(identity.get(platform, "/api/identity/platform/orgs/" + orgId), "company details").body();
        assertEquals(adminEmail, details.path("primaryContact").path("email").asText());
        assertEquals("Test Admin", details.path("primaryContact").path("name").asText());
        assertEquals(1, details.path("administrators").size());
        assertEquals(1, details.path("userStats").path("total").asInt());
        assertTrue(details.path("roleCount").asInt() > 0);
        assertEquals(404, identity.get(platform, "/api/identity/platform/orgs/" + UUID.randomUUID()).status());
        assertEquals(403, identity.get(Flow.env().token("ORG_ADMIN", "Harborview"), "/api/identity/platform/orgs/" + orgId).status());

        JsonNode matrix = Flow.ok(identity.get(platform, "/api/identity/platform/orgs/" + orgId + "/features"), "feature matrix").body();
        String feature = matrix.get(0).path("feature").asText();
        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + orgId + "/features", Map.of(feature, false)), "turn feature off");
        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + orgId + "/features", Flow.map(feature, null)), "reset feature");
        assertEquals(400, identity.put(platform, "/api/identity/platform/orgs/" + orgId + "/features", Map.of("NOT_A_FEATURE", true)).status());
        assertEquals(400, identity.put(platform, "/api/identity/platform/orgs/" + orgId + "/features", Map.of(feature, "maybe")).status());
        assertEquals(404, identity.get(platform, "/api/identity/platform/orgs/" + UUID.randomUUID() + "/features").status());

        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + orgId, Map.of("plan", "ENTERPRISE", "status", "ACTIVE")), "change plan");
        assertEquals(400, identity.put(platform, "/api/identity/platform/orgs/" + orgId, Map.of("plan", "GALACTIC")).status());
        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + orgId, Map.of("status", "SUSPENDED")), "suspend");
        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + orgId, Map.of("status", "ACTIVE")), "reactivate");

        String admin = Flow.token("ORG_ADMIN");
        assertEquals(403, identity.post(admin, "/api/identity/platform/orgs", Map.of("name", "x")).status());
        assertEquals(403, identity.put(admin, "/api/identity/platform/orgs/" + orgId, Map.of("plan", "STARTER")).status());
        soft.assertClean();
    }

    @Test
    void demoResetIsRestrictedToAdministrators() {
        // The successful reset reseeds every service asynchronously, which would disturb the other scenarios, so only the guard is exercised.
        assertEquals(403, identity.post(Flow.token("TENANT"), "/api/identity/demo/reset", Map.of()).status());
    }

    /**
     * One company's administrator must never see another company's roles or people, or be able to hand out another
     * company's roles (above all the platform operator's). This guards the database-level isolation that the
     * services depend on, including on managed databases where the admin user can otherwise bypass it.
     */
    @Test
    void companyAdministratorSeesOnlyTheirOwnCompany() {
        String harborview = Flow.env().token("ORG_ADMIN", "Harborview");
        String summit = Flow.env().token("ORG_ADMIN", "Summit");

        JsonNode roles = Flow.ok(identity.get(harborview, "/api/identity/roles"), "roles").body();
        java.util.Set<String> keys = new java.util.HashSet<>();
        for (JsonNode r : roles) {
            assertTrue(keys.add(r.path("role_key").asText()), "each role appears once, but saw a duplicate: " + r.path("role_key").asText());
            assertFalse("PLATFORM_ADMIN".equals(r.path("role_key").asText()), "an organisation administrator must not see the Platform Admin role");
        }

        for (JsonNode u : Flow.ok(identity.get(harborview, "/api/identity/users"), "users").body()) {
            assertTrue(u.path("email").asText().endsWith("@harborviewres.com"), "another company's person is visible: " + u.path("email").asText());
        }

        // Handing out a role that belongs to another company is refused.
        String summitRole = Flow.ok(identity.get(summit, "/api/identity/roles"), "summit roles").body().get(0).path("id").asText();
        Reply foreign = identity.post(harborview, "/api/identity/users",
                Map.of("email", "isolation-" + UUID.randomUUID() + "@harborviewres.com", "name", "Isolation Check", "roleIds", List.of(summitRole)));
        assertEquals(400, foreign.status(), "assigning another company's role must be refused: " + foreign.body());
    }

    /**
     * The public website reads plans and features from the platform, the contact form stores enquiries only the operator
     * can see, and the operator can change how plans are presented without touching what a plan includes.
     */
    @Test
    void publicWebsiteIsDynamicAndEnquiriesStayWithTheOperator() {
        String platform = Flow.token("PLATFORM_ADMIN");
        String companyAdmin = Flow.env().token("ORG_ADMIN", "Harborview");
        String site = "/api/identity/public/site";

        JsonNode open = Flow.ok(identity.get(null, site), "public site").body();
        assertEquals(3, open.path("plans").size());
        assertTrue(open.path("features").size() >= 10);
        JsonNode growth = null;
        for (JsonNode p : open.path("plans")) {
            if ("GROWTH".equals(p.path("key").asText())) growth = p;
            if ("ENTERPRISE".equals(p.path("key").asText())) assertTrue(p.path("allFeatures").asBoolean(), "Enterprise includes every feature");
        }
        assertTrue(growth != null && growth.path("highlight").asBoolean() && growth.path("pricePerUnit").asDouble() > 0);

        // Contact form: validation, a real enquiry, and the bot trap.
        String contact = "/api/identity/public/contact";
        assertEquals(400, identity.post(null, contact, Map.of("name", "A", "email", "x", "message", "short")).status());
        assertEquals(400, identity.post(null, contact, Map.of("name", "Pat Lee", "email", "pat@example.test", "message", "Hello there, I would like a demo.", "topic", "NOPE")).status());
        String marker = "Enquiry " + UUID.randomUUID();
        Flow.ok(identity.post(null, contact, Flow.map("name", "Pat Lee", "email", "Pat@Example.test", "company", marker, "units", "250",
                "topic", "pricing", "message", "We manage 250 doors and would like pricing.")), "contact form");
        assertEquals(200, identity.post(null, contact, Flow.map("name", "Bot", "email", "bot@example.test", "company", "Bot " + marker,
                "message", "Buy cheap watches now please", "website", "http://spam.test")).status());

        JsonNode inbox = Flow.ok(identity.get(platform, "/api/identity/platform/site/contact-requests"), "enquiries").body();
        String id = null;
        for (JsonNode r : inbox) {
            assertFalse(r.path("company").asText().startsWith("Bot "), "a bot submission must not be stored");
            if (marker.equals(r.path("company").asText())) {
                id = r.path("id").asText();
                assertEquals("pat@example.test", r.path("email").asText());
                assertEquals("PRICING", r.path("topic").asText());
                assertEquals("NEW", r.path("status").asText());
            }
        }
        assertTrue(id != null, "the enquiry reaches the operator");
        assertEquals(403, identity.get(companyAdmin, "/api/identity/platform/site/contact-requests").status());
        assertEquals(401, identity.get(null, "/api/identity/platform/site/contact-requests").status());
        assertEquals(400, identity.put(platform, "/api/identity/platform/site/contact-requests/" + id, Map.of("status", "WHATEVER")).status());
        Flow.ok(identity.put(platform, "/api/identity/platform/site/contact-requests/" + id, Map.of("status", "contacted")), "mark contacted");
        assertEquals(1, Flow.ok(identity.get(platform, "/api/identity/platform/site/contact-requests?status=CONTACTED"), "filter").body().findValues("company").stream()
                .filter(c -> marker.equals(c.asText())).count());

        // The operator manages plans; the public pages follow, and a company admin cannot.
        String plans = "/api/identity/platform/plans";
        assertEquals(403, identity.put(companyAdmin, plans + "/STARTER", Map.of("tagline", "Hacked")).status());
        assertEquals(403, identity.post(companyAdmin, plans, Map.of("key", "X1", "name", "X")).status());
        assertEquals(403, identity.get(companyAdmin, plans).status());
        assertEquals(400, identity.put(platform, plans + "/STARTER", Map.of("pricePerUnit", "cheap")).status());
        assertEquals(404, identity.put(platform, plans + "/NOPE", Map.of("tagline", "x")).status());
        assertEquals(400, identity.put(platform, plans + "/STARTER", Map.of("features", List.of("NOT_A_FEATURE"))).status());

        String key = "TEST" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        assertEquals(400, identity.post(platform, plans, Flow.map("key", "bad key!", "name", "Bad")).status());
        Flow.ok(identity.post(platform, plans, Flow.map("key", key, "name", "Custom " + key, "tagline", "Made for the test", "pricePerUnit", "3.50",
                "minimumMonthly", "200", "features", List.of("LEASING", "PAYMENTS"), "highlight", true)), "create plan");
        assertEquals(409, identity.post(platform, plans, Flow.map("key", key, "name", "Again")).status());
        JsonNode created = null;
        int popular = 0;
        for (JsonNode p : Flow.ok(identity.get(null, site), "site with new plan").body().path("plans")) {
            if (p.path("highlight").asBoolean()) popular++;
            if (key.equals(p.path("key").asText())) created = p;
        }
        assertTrue(created != null && created.path("pricePerUnit").asDouble() == 3.5 && created.path("features").size() == 2, "a new plan appears on the website");
        assertEquals(1, popular, "only one plan is marked most popular");

        // A company on the plan gets exactly the plan's features, and changing the plan changes the company.
        String org = Flow.ok(identity.post(platform, "/api/identity/platform/orgs", Flow.map("name", "Plan Co " + marker, "state", "TX", "plan", key,
                "adminName", "Plan Admin", "adminEmail", "plan." + marker.replace(" ", "") + "@example.test")), "onboard onto the new plan").body().path("orgId").asText();
        assertEquals(409, identity.delete(platform, plans + "/" + key).status(), "a plan with companies can't be deleted");
        JsonNode matrix = Flow.ok(identity.get(platform, "/api/identity/platform/orgs/" + org + "/features"), "feature matrix").body();
        for (JsonNode f : matrix) assertEquals(List.of("LEASING", "PAYMENTS").contains(f.path("feature").asText()), f.path("enabled").asBoolean(), f.path("feature").asText());
        Flow.ok(identity.put(platform, plans + "/" + key, Flow.map("features", List.of("LEASING", "PAYMENTS", "MAINTENANCE"), "name", "Renamed " + key)), "add a feature to the plan");
        for (JsonNode f : Flow.ok(identity.get(platform, "/api/identity/platform/orgs/" + org + "/features"), "matrix again").body())
            if ("MAINTENANCE".equals(f.path("feature").asText())) assertTrue(f.path("enabled").asBoolean(), "the plan's new feature reaches its companies");
        Flow.ok(identity.put(platform, plans + "/" + key, Flow.map("features", List.of("*"))), "all features");
        Flow.ok(identity.put(platform, "/api/identity/platform/orgs/" + org, Map.of("plan", "STARTER")), "move the company to Starter");
        assertEquals(400, identity.put(platform, "/api/identity/platform/orgs/" + org, Map.of("plan", "NO_SUCH_PLAN")).status());
        Flow.ok(identity.delete(platform, plans + "/" + key), "delete the empty plan");
        for (JsonNode p : Flow.ok(identity.get(null, site), "site after delete").body().path("plans")) assertFalse(key.equals(p.path("key").asText()));
        Flow.ok(identity.put(platform, plans + "/GROWTH", Map.of("highlight", true)), "restore highlight");
        assertEquals(200, identity.get(platform, plans).status());

        String feature = "/api/identity/platform/site/features/ESIGN";
        Flow.ok(identity.put(platform, feature, Map.of("visible", false)), "hide a feature");
        for (JsonNode f : Flow.ok(identity.get(null, site), "site without feature").body().path("features")) assertFalse("ESIGN".equals(f.path("key").asText()));
        for (JsonNode p : Flow.ok(identity.get(null, site), "plans without feature").body().path("plans")) for (JsonNode f : p.path("features")) assertFalse("ESIGN".equals(f.asText()));
        Flow.ok(identity.put(platform, feature, Map.of("visible", true)), "show it again");
        assertEquals(400, identity.put(platform, feature, Map.of("title", "")).status());
        assertEquals(200, identity.get(platform, "/api/identity/platform/site/features").status());
    }
}
