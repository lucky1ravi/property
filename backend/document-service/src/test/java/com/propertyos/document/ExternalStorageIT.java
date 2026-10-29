package com.propertyos.document;

import com.propertyos.common.security.JwtService;
import com.propertyos.common.security.UserContext;
import com.propertyos.document.storage.FileStore;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.ApiClient.Reply;
import com.propertyos.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Files kept outside the database: the row holds only the object key, the bytes come back through the store, and
 * removing the file removes the object. An in-memory store stands in for S3, so the real SQL and wiring are what is tested.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class ExternalStorageIT {
    private static final int PORT = PostgresTestDatabase.freePort();
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");
    private static final Map<String, byte[]> OBJECTS = new ConcurrentHashMap<>();
    private static ApiClient api;

    /** A store that behaves like S3: bytes go in a map under a key, and the row keeps only the key. */
    @TestConfiguration
    static class InMemoryStore {
        @Bean
        @Primary
        FileStore objectStore() {
            return new FileStore() {
                @Override public Placement put(UUID orgId, String area, UUID id, byte[] data, String mimeType) {
                    String key = orgId + "/" + area + "/" + id;
                    OBJECTS.put(key, data);
                    return new Placement(key, null);
                }
                @Override public byte[] read(String key, byte[] inline) { return key == null ? inline : OBJECTS.get(key); }
                @Override public void delete(String key) { if (key != null) OBJECTS.remove(key); }
            };
        }
    }

    @Autowired JwtService jwt;
    @Autowired JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("server.port", () -> PORT);
        registry.add("propertyos.demo.enabled", () -> "false");
        registry.add("propertyos.jwt.secret", () -> "external-storage-test-secret-0123456789-abcdefghij");
        registry.add("propertyos.internal-token", () -> "external-storage-internal-token");
        registry.add("propertyos.services.ai", () -> "http://localhost:" + PostgresTestDatabase.freePort());
    }

    @BeforeAll
    static void client() {
        api = new ApiClient(PORT);
    }

    private String token(UUID org, UUID user, String... permissions) {
        return jwt.issue(new UserContext(user, org, "Test User", "test@example.com", List.of("Tester"), Set.of(permissions), UserContext.SCOPE_ORG,
                null, List.of(), Map.of(), Map.of(), false), Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("a photo's bytes go to the object store, the row keeps only the key, and removing the photo removes the object")
    void photoLivesInObjectStore() {
        UUID org = UUID.randomUUID();
        String token = token(org, UUID.randomUUID(), "MEDIA:UPLOAD");

        Reply uploaded = api.upload(token, "/api/documents/media", "ext-" + UUID.randomUUID() + ".png", PNG, Map.of("kind", "PROFILE"));
        assertThat(uploaded.status()).as("upload: %s", uploaded.body()).isEqualTo(200);
        String id = uploaded.body().path("id").asText();

        Map<String, Object> row = jdbc.queryForMap("select content is null as no_bytes, storage_key from document.media where id = ?", UUID.fromString(id));
        assertThat(row.get("no_bytes")).isEqualTo(true);
        String key = (String) row.get("storage_key");
        assertThat(key).startsWith(org + "/photos/");
        assertThat(OBJECTS).containsKey(key);

        assertThat(api.status(token, "/api/documents/media/" + id + "/content")).isEqualTo(200);

        assertThat(api.delete(token, "/api/documents/media/" + id).status()).isEqualTo(200);
        assertThat(OBJECTS).doesNotContainKey(key);
    }

    @Test
    @DisplayName("a document's bytes go to the object store and download reads them back")
    void documentLivesInObjectStore() {
        UUID org = UUID.randomUUID();
        String token = token(org, UUID.randomUUID(), "DOCUMENT:VIEW", "DOCUMENT:CREATE", "DOCUMENT:DELETE", "DOCUMENT:CONFIGURE");

        Reply uploaded = api.upload(token, "/api/documents", "note-" + UUID.randomUUID() + ".txt", ("hello " + UUID.randomUUID()).getBytes(),
                Map.of("title", "Storage test", "category", "OTHER"));
        assertThat(uploaded.status()).as("upload: %s", uploaded.body()).isEqualTo(200);
        String id = uploaded.body().path("id").asText();

        Map<String, Object> row = jdbc.queryForMap("select b.content is null as no_bytes, b.storage_key from document.document_version v join document.blob b on b.id = v.blob_id where v.document_id = ?", UUID.fromString(id));
        assertThat(row.get("no_bytes")).isEqualTo(true);
        assertThat(OBJECTS).containsKey((String) row.get("storage_key"));

        assertThat(api.status(token, "/api/documents/" + id + "/file")).isEqualTo(200);
    }
}
