package com.propertyos.system;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.testsupport.ApiClient;
import com.propertyos.testsupport.PostgresTestDatabase;
import org.jacoco.core.tools.ExecFileLoader;
import org.jacoco.core.tools.ExecDumpClient;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Starts the real platform for the system tests: a PostgreSQL database and every service as its own JVM, built from the
 * same jars that are deployed, with demo data. A JaCoCo agent is attached to each JVM so the coverage gate can see which
 * code the tests exercised.
 * <p>
 * One instance is shared by every test class in the run. It is created on first use and stopped by a JVM shutdown hook.
 */
public final class SystemEnvironment {
    /** Services in start order. Identity goes first because it creates the shared database objects the others rely on. */
    public static final List<String> SERVICES = List.of("identity-service", "leasing-service", "billing-service",
            "maintenance-service", "workflow-service", "ai-service", "document-service", "api-gateway");

    private static SystemEnvironment instance;
    private boolean external;
    private FakeOpenAi fakeModel;
    private int fakeModelPort;

    private final Path modulesDir = Path.of(System.getProperty("sys.modules.dir", "..")).toAbsolutePath().normalize();
    private final Path workDir = Path.of("target", "system").toAbsolutePath();
    private final Map<String, Integer> ports = new LinkedHashMap<>();
    private final Map<String, Integer> agentPorts = new LinkedHashMap<>();
    private final Map<String, Process> processes = new LinkedHashMap<>();
    private final Map<String, String> tokens = new LinkedHashMap<>();

    /** Returns the shared environment, starting it on first use. */
    public static synchronized SystemEnvironment get() {
        if (instance == null) {
            instance = new SystemEnvironment();
            try {
                if (Boolean.getBoolean("sys.external")) instance.attachToRunningStack();
                else instance.start();
            } catch (Exception e) {
                instance.stop();
                instance = null;
                throw new IllegalStateException("Could not start the platform for system tests: " + e.getMessage(), e);
            }
            SystemEnvironment started = instance;
            Runtime.getRuntime().addShutdownHook(new Thread(started::stop));
        }
        return instance;
    }

    /** Port of a service, for example {@code "billing-service"}. */
    public int port(String service) {
        return ports.get(service);
    }

    /** A client for one service's HTTP API. */
    public ApiClient api(String service) {
        return new ApiClient(port(service));
    }

    /** Directory containing every module (the backend folder). */
    public Path modulesDir() {
        return modulesDir;
    }

    /** Where the merged coverage data and reports are written. */
    public Path workDir() {
        return workDir;
    }

    /**
     * Signs in a demo user and caches the token.
     *
     * @param roleKey role of the user, for example {@code ORG_ADMIN}
     * @param org     part of the company name, for example {@code Summit}
     * @return the bearer token
     */
    public synchronized String token(String roleKey, String org) {
        return tokens.computeIfAbsent(roleKey + "@" + org, k -> {
            ApiClient identity = api("identity-service");
            JsonNode users = identity.get(null, "/api/identity/public/demo-users").body().path("users");
            for (JsonNode u : users) {
                if (roleKey.equals(u.path("roleKey").asText()) && u.path("organization").asText().contains(org)) {
                    ApiClient.Reply r = identity.post(null, "/api/identity/public/demo-login", Map.of("userId", u.path("id").asText()));
                    if (r.status() != 200) throw new AssertionError("Demo sign-in failed for " + roleKey + ": " + r.body());
                    return r.body().path("token").asText();
                }
            }
            throw new AssertionError("No demo user with role " + roleKey + " in " + org);
        });
    }

    /** Signs in a demo user by id. */
    public String tokenForUser(String userId) {
        ApiClient.Reply r = api("identity-service").post(null, "/api/identity/public/demo-login", Map.of("userId", userId));
        if (r.status() != 200) throw new AssertionError("Demo sign-in failed for user " + userId + ": " + r.body());
        return r.body().path("token").asText();
    }

    /** Every role key present among the demo users. */
    public List<String> demoRoleKeys() {
        List<String> out = new ArrayList<>();
        for (JsonNode u : api("identity-service").get(null, "/api/identity/public/demo-users").body().path("users")) {
            String key = u.path("roleKey").asText();
            if (!out.contains(key)) out.add(key);
        }
        return out;
    }

    /** Developer shortcut: use services already running on ports 19081.. (in {@link #SERVICES} order) instead of starting new ones. */
    private void attachToRunningStack() {
        int p = 19081;
        for (String s : SERVICES) {
            ports.put(s, p);
            agentPorts.put(s, p + 10000);
            p++;
        }
        external = true;
        fakeModelPort = 19100;
        startFakeModel();
    }

    private void startFakeModel() {
        try {
            fakeModel = new FakeOpenAi(fakeModelPort);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stand-in AI model on port " + fakeModelPort + ": " + e.getMessage(), e);
        }
    }

    private void start() throws Exception {
        Files.createDirectories(workDir);
        PostgresTestDatabase.Connection db = PostgresTestDatabase.connection();
        String database = "sys_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection c = DriverManager.getConnection(db.jdbcUrl(), db.user(), db.password()); Statement s = c.createStatement()) {
            s.execute("create database " + database);
        }
        String url = db.jdbcUrl();
        String withoutParams = url.contains("?") ? url.substring(0, url.indexOf('?')) : url;
        String hostPort = withoutParams.substring("jdbc:postgresql://".length(), withoutParams.lastIndexOf('/'));
        String host = hostPort.contains(":") ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort;
        String port = hostPort.contains(":") ? hostPort.substring(hostPort.indexOf(':') + 1) : "5432";

        for (String s : SERVICES) {
            ports.put(s, PostgresTestDatabase.freePort());
            agentPorts.put(s, PostgresTestDatabase.freePort());
        }
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PGHOST", host);
        env.put("PGPORT", port);
        env.put("PGDATABASE", database);
        env.put("PGUSER", db.user());
        env.put("PGPASSWORD", db.password());
        env.put("DEMO_MODE", "true");
        env.put("DB_POOL_SIZE", "5");
        env.put("IDENTITY_URL", "http://localhost:" + ports.get("identity-service"));
        env.put("LEASING_URL", "http://localhost:" + ports.get("leasing-service"));
        env.put("BILLING_URL", "http://localhost:" + ports.get("billing-service"));
        env.put("MAINTENANCE_URL", "http://localhost:" + ports.get("maintenance-service"));
        env.put("WORKFLOW_URL", "http://localhost:" + ports.get("workflow-service"));
        env.put("AI_URL", "http://localhost:" + ports.get("ai-service"));
        env.put("DOCUMENTS_URL", "http://localhost:" + ports.get("document-service"));
        env.put("RATE_LIMIT_PUBLIC_PER_MINUTE", "100000");
        env.put("RATE_LIMIT_USER_PER_MINUTE", "100000");

        fakeModelPort = PostgresTestDatabase.freePort();
        startFakeModel();
        launch("identity-service", env);
        waitHealthy("identity-service", 240);
        for (String s : SERVICES.subList(1, SERVICES.size())) launch(s, env);
        for (String s : SERVICES.subList(1, SERVICES.size())) waitHealthy(s, 240);
        awaitDemoData();
    }

    private void launch(String service, Map<String, String> env) throws IOException {
        File jar = modulesDir.resolve(service).resolve("target").resolve("app.jar").toFile();
        if (!jar.isFile()) throw new IllegalStateException(jar + " is missing. Run the build first (mvn verify builds every module before this one).");
        String agent = System.getProperty("sys.jacoco.agent");
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(List.of("-Xmx300m", "-XX:+UseSerialGC", "-Xss512k"));
        if (agent != null && new File(agent).isFile()) {
            cmd.add("-javaagent:" + agent + "=output=tcpserver,address=127.0.0.1,port=" + agentPorts.get(service) + ",includes=com.propertyos.*");
        }
        cmd.addAll(List.of("-jar", jar.getPath()));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().putAll(env);
        pb.environment().put("PORT", String.valueOf(ports.get(service)));
        if ("ai-service".equals(service)) {
            // The AI service talks to a stand-in model run by these tests, so every AI code path can be exercised offline.
            pb.environment().put("AI_PROVIDER", "openai");
            pb.environment().put("OPENAI_API_KEY", "test-key");
            pb.environment().put("OPENAI_MODEL", "fake-model");
            pb.environment().put("OPENAI_BASE_URL", "http://127.0.0.1:" + fakeModelPort + "/v1");
        }
        pb.redirectErrorStream(true);
        pb.redirectOutput(workDir.resolve(service + ".log").toFile());
        processes.put(service, pb.start());
    }

    private void waitHealthy(String service, int seconds) throws InterruptedException {
        ApiClient client = api(service);
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!processes.get(service).isAlive()) throw new IllegalStateException(service + " stopped during start-up; see " + workDir.resolve(service + ".log"));
            try {
                if (client.get(null, "/actuator/health").status() == 200) return;
            } catch (RuntimeException ignored) {
                // not listening yet
            }
            Thread.sleep(1500);
        }
        throw new IllegalStateException(service + " did not become healthy within " + seconds + " seconds");
    }

    /** Waits until the demo seeders in the other services have finished, so tests start from a populated portfolio. */
    private void awaitDemoData() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 240_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String admin = token("ORG_ADMIN", "Summit");
                boolean ready = api("leasing-service").get(admin, "/api/leasing/properties").body().size() > 0
                        && api("billing-service").get(admin, "/api/billing/invoices").body().size() > 0
                        && api("maintenance-service").get(admin, "/api/maintenance/work-orders").body().size() > 0
                        && api("document-service").get(admin, "/api/documents").body().path("items").size() > 0;
                if (ready) return;
            } catch (RuntimeException | AssertionError ignored) {
                // seeders still running
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("Demo data was not ready in time; see the logs in " + workDir);
    }

    /**
     * Collects the coverage recorded in every service JVM so far.
     *
     * @return one data store holding all of it
     */
    public ExecFileLoader dumpCoverage() throws IOException {
        ExecFileLoader all = new ExecFileLoader();
        ExecDumpClient client = new ExecDumpClient();
        client.setDump(true);
        client.setReset(false);
        client.setRetryCount(5);
        for (String s : SERVICES) {
            if (!external && (processes.get(s) == null || !processes.get(s).isAlive())) continue;
            ExecFileLoader one = client.dump("127.0.0.1", agentPorts.get(s));
            all.load(new java.io.ByteArrayInputStream(toBytes(one)));
        }
        return all;
    }

    private static byte[] toBytes(ExecFileLoader loader) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.ByteArrayOutputStream tmp = out) {
            File f = File.createTempFile("jacoco", ".exec");
            try {
                loader.save(f, false);
                tmp.write(Files.readAllBytes(f.toPath()));
            } finally {
                f.delete();
            }
        }
        return out.toByteArray();
    }

    /** Stops every service. */
    public synchronized void stop() {
        processes.values().forEach(Process::destroy);
        for (Process p : processes.values()) {
            try {
                if (!p.waitFor(20, TimeUnit.SECONDS)) p.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.destroyForcibly();
            }
        }
        processes.clear();
        if (fakeModel != null) fakeModel.stop();
    }
}
