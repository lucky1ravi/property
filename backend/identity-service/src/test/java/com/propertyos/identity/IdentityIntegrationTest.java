package com.propertyos.identity;

import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.PostgresTestDatabase;
import org.junit.jupiter.api.BeforeAll;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the identity service's Docker-backed integration tests. It boots the whole service against a real
 * PostgreSQL database (so Flyway migrations and row-level security run for real) with the demo companies seeded.
 * All subclasses share one Spring context and one database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
abstract class IdentityIntegrationTest {
    static final int PORT = PostgresTestDatabase.freePort();
    static final String DEMO_PASSWORD = "PropertyOS@2026";
    static final String PLATFORM_ADMIN = "alex.morgan@propertyos.io";
    static final String SUMMIT_ADMIN = "sarah.mitchell@summitridgepg.com";

    static ApiClient api;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        PostgresTestDatabase.register(registry);
        registry.add("server.port", () -> PORT);
        // The service reads company settings from itself over HTTP, as the other services do.
        registry.add("propertyos.services.identity", () -> "http://localhost:" + PORT);
        registry.add("propertyos.demo.enabled", () -> "true");
    }

    @BeforeAll
    static void client() {
        api = new ApiClient(PORT);
    }
}
