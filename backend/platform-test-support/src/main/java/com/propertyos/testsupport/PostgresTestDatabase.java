package com.propertyos.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One PostgreSQL database shared by every integration test class in a JVM.
 * <p>
 * By default it starts a throw-away PostgreSQL 16 container with Testcontainers, so the tests need only Docker.
 * Set {@code IT_JDBC_URL} (plus {@code IT_DB_USER} and {@code IT_DB_PASSWORD}) to run against an existing database instead,
 * for example a CI service container or a local instance.
 */
public final class PostgresTestDatabase {
    /**
     * Docker image used for the test database.
     */
    private static final String IMAGE = "postgres:16-alpine";
    private static PostgreSQLContainer<?> container;

    private PostgresTestDatabase() { }

    /** Points the application's datasource at the test database. Call from a {@code @DynamicPropertySource} method. */
    public static void register(DynamicPropertyRegistry registry) {
        String external = System.getenv("IT_JDBC_URL");
        if (external != null && !external.isBlank()) {
            registry.add("spring.datasource.url", () -> external);
            registry.add("spring.datasource.username", () -> envOr("IT_DB_USER", "postgres"));
            registry.add("spring.datasource.password", () -> envOr("IT_DB_PASSWORD", "postgres"));
            return;
        }
        PostgreSQLContainer<?> db = start();
        registry.add("spring.datasource.url", db::getJdbcUrl);
        registry.add("spring.datasource.username", db::getUsername);
        registry.add("spring.datasource.password", db::getPassword);
    }

    private static synchronized PostgreSQLContainer<?> start() {
        if (container == null) {
            container = new PostgreSQLContainer<>(DockerImageName.parse(IMAGE)).withDatabaseName("propertyos");
            container.start(); // stopped by Testcontainers' Ryuk when the JVM exits
        }
        return container;
    }

    /** Connection details of the test database, for code that starts other processes against it. */
    public record Connection(String jdbcUrl, String user, String password) { }

    /**
     * Returns how to reach the test database, starting the container first if one is needed.
     *
     * @return the JDBC URL and credentials
     */
    public static Connection connection() {
        String external = System.getenv("IT_JDBC_URL");
        if (external != null && !external.isBlank()) {
            return new Connection(external, envOr("IT_DB_USER", "postgres"), envOr("IT_DB_PASSWORD", "postgres"));
        }
        PostgreSQLContainer<?> db = start();
        return new Connection(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private static String envOr(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    /** A free TCP port, for tests that need the service to listen on a known address (a service that calls itself, for example). */
    public static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No free port available", e);
        }
    }
}
