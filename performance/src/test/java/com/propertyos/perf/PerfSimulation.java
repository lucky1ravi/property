package com.propertyos.perf;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.util.List;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.http.HttpDsl.http;

/**
 * Common setup for every simulation: the HTTP settings, the signed-in personas, the user mix and the service levels the run must meet.
 * Concrete simulations only choose how load arrives over time.
 */
abstract class PerfSimulation extends Simulation {
    /** Share of virtual users per journey. */
    protected static final double MANAGERS = 0.40, TENANTS = 0.30, ACCOUNTANTS = 0.10, PROSPECTS = 0.20;

    protected final HttpProtocolBuilder protocol = http.baseUrl(Config.BASE_URL)
            .acceptHeader("application/json").userAgentHeader("propertyos-performance-suite")
            .acceptEncodingHeader("gzip").shareConnections();

    private final Map<String, String> tokens = Auth.signInAll();

    protected ScenarioBuilder managers() { return Journeys.propertyManager(tokens); }

    protected ScenarioBuilder tenants() { return Journeys.tenant(tokens); }

    protected ScenarioBuilder accountants() { return Journeys.accountant(tokens); }

    protected ScenarioBuilder prospects() { return Journeys.prospect(); }

    /** The number of users for one journey, at least one. */
    protected static int share(int users, double part) {
        return Math.max(1, (int) Math.round(users * part));
    }

    /** The service levels: failed-request rate and 95th percentile response time across all APIs. */
    protected static List<Assertion> serviceLevels(int p95Ms, double maxErrorPercent) {
        return List.of(
                global().failedRequests().percent().lte(maxErrorPercent),
                global().responseTime().percentile(95).lt(p95Ms),
                global().successfulRequests().count().gt(0L));
    }
}
