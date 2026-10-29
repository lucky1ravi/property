package com.propertyos.document;

import com.propertyos.common.security.JwtService;
import com.propertyos.common.security.UserContext;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import com.propertyos.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The photo review workflow against a real database. The image-checking service is deliberately not running,
 * which also proves the safe default: when a photo cannot be checked it waits for a person instead of being published.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class PhotoReviewIT {
    private static final int PORT = PostgresTestDatabase.freePort();
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    private static ApiClient api;

    @Autowired JwtService jwt;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("server.port", () -> PORT);
        registry.add("propertyos.demo.enabled", () -> "false");
        // Production mode refuses development secrets, so this test supplies its own.
        registry.add("propertyos.jwt.secret", () -> "photo-review-test-secret-0123456789-abcdefghij");
        registry.add("propertyos.internal-token", () -> "photo-review-internal-token");
        registry.add("propertyos.services.ai", () -> "http://localhost:" + PostgresTestDatabase.freePort()); // nothing listens here
    }

    @BeforeAll
    static void client() {
        api = new ApiClient(PORT);
    }

    private String token(UUID org, UUID user, String... permissions) {
        return jwt.issue(new UserContext(user, org, "Test User", "test@example.com", List.of("Tester"), Set.of(permissions), UserContext.SCOPE_ORG,
                null, List.of(), Map.of(), Map.of(), false), Duration.ofMinutes(10));
    }

    private Reply uploadProfilePhoto(String token) {
        return api.upload(token, "/api/documents/media", "me-" + UUID.randomUUID() + ".png", PNG, Map.of("kind", "PROFILE"));
    }

    @Test
    @DisplayName("a photo that cannot be checked waits for review and is not public")
    void unverifiedPhotoIsNotPublished() {
        String uploader = token(UUID.randomUUID(), UUID.randomUUID(), "MEDIA:UPLOAD");

        Reply uploaded = uploadProfilePhoto(uploader);

        assertThat(uploaded.status()).as("upload: %s", uploaded.body()).isEqualTo(200);
        assertThat(uploaded.body().path("status").asText()).isEqualTo("PENDING_REVIEW");
        assertThat(uploaded.body().path("publicUrl").isNull() || uploaded.body().path("publicUrl").isMissingNode()).isTrue();
        assertThat(api.status(null, "/api/documents/public/media/" + uploaded.body().path("id").asText())).as("anonymous access").isEqualTo(404);
    }

    @Test
    @DisplayName("a reviewer can approve a photo, and only then does it become public")
    void reviewerApproves() {
        UUID org = UUID.randomUUID();
        String uploader = token(org, UUID.randomUUID(), "MEDIA:UPLOAD");
        String reviewer = token(org, UUID.randomUUID(), "MEDIA:UPLOAD", "MEDIA:REVIEW");
        String id = uploadProfilePhoto(uploader).body().path("id").asText();

        Reply approved = api.post(reviewer, "/api/documents/media/" + id + "/decision", Map.of("decision", "APPROVE"));

        assertThat(approved.status()).as("approve: %s", approved.body()).isEqualTo(200);
        assertThat(approved.body().path("status").asText()).isEqualTo("APPROVED");
        assertThat(api.status(null, "/api/documents/public/media/" + id)).isEqualTo(200);
    }

    @Test
    @DisplayName("rejecting a photo needs a reason and keeps it private")
    void rejectionNeedsReason() {
        UUID org = UUID.randomUUID();
        String uploader = token(org, UUID.randomUUID(), "MEDIA:UPLOAD");
        String reviewer = token(org, UUID.randomUUID(), "MEDIA:UPLOAD", "MEDIA:REVIEW");
        String id = uploadProfilePhoto(uploader).body().path("id").asText();

        assertThat(api.post(reviewer, "/api/documents/media/" + id + "/decision", Map.of("decision", "REJECT")).status()).isEqualTo(400);
        Reply rejected = api.post(reviewer, "/api/documents/media/" + id + "/decision", Map.of("decision", "REJECT", "note", "Photo is blurry"));
        assertThat(rejected.body().path("status").asText()).isEqualTo("REJECTED");
        assertThat(api.status(null, "/api/documents/public/media/" + id)).isEqualTo(404);
    }

    @Test
    @DisplayName("someone without the review permission cannot approve photos")
    void uploaderCannotApproveOwnPhoto() {
        String uploader = token(UUID.randomUUID(), UUID.randomUUID(), "MEDIA:UPLOAD");
        String id = uploadProfilePhoto(uploader).body().path("id").asText();

        Reply attempt = api.post(uploader, "/api/documents/media/" + id + "/decision", Map.of("decision", "APPROVE"));

        assertThat(attempt.status()).isEqualTo(403);
        assertThat(api.status(null, "/api/documents/public/media/" + id)).isEqualTo(404);
    }

    @Test
    @DisplayName("another company cannot see or review a company's photos")
    void photosAreIsolatedByCompany() {
        UUID org = UUID.randomUUID();
        String id = uploadProfilePhoto(token(org, UUID.randomUUID(), "MEDIA:UPLOAD")).body().path("id").asText();
        String outsider = token(UUID.randomUUID(), UUID.randomUUID(), "MEDIA:UPLOAD", "MEDIA:REVIEW");

        assertThat(api.get(outsider, "/api/documents/media/" + id).status()).isEqualTo(404);
        assertThat(api.post(outsider, "/api/documents/media/" + id + "/decision", Map.of("decision", "APPROVE")).status()).isEqualTo(404);
    }

    @Test
    @DisplayName("files that are not images are refused")
    void nonImageRefused() {
        String uploader = token(UUID.randomUUID(), UUID.randomUUID(), "MEDIA:UPLOAD");
        Reply r = api.upload(uploader, "/api/documents/media", "payload.exe", "MZ not an image".getBytes(), Map.of("kind", "PROFILE"));
        assertThat(r.status()).isBetween(400, 415);
    }

    @Test
    @DisplayName("uploading the same photo twice is a conflict")
    void duplicateUpload() {
        String uploader = token(UUID.randomUUID(), UUID.randomUUID(), "MEDIA:UPLOAD");
        String name = "dup.png";
        assertThat(api.upload(uploader, "/api/documents/media", name, PNG, Map.of("kind", "PROFILE")).status()).isEqualTo(200);
        assertThat(api.upload(uploader, "/api/documents/media", name, PNG, Map.of("kind", "PROFILE")).status()).isEqualTo(409);
    }
}
