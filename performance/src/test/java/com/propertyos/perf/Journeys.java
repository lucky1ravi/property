package com.propertyos.perf;

import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;

import java.time.Duration;
import java.util.Map;

import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.feed;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.listFeeder;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * What real people do, as sequences of API calls with think time between them.
 * Every request is named, so the report shows the response time of each API separately.
 * Staff journeys end with a small share of writes; the data they create is harmless (work orders titled "Perf test").
 */
final class Journeys {
    private Journeys() {
    }

    private static final String AUTH = "Bearer #{token}";

    /** A read as a signed-in user. Fails the request unless the API answers 200. */
    private static ChainBuilder read(String name, String path) {
        return exec(http(name).get(path).header("Authorization", AUTH).check(status().is(200)));
    }

    private static ChainBuilder think(int minSeconds, int maxSeconds) {
        return exec(pause(Duration.ofSeconds(minSeconds), Duration.ofSeconds(maxSeconds)));
    }

    /** Property manager: opens the dashboard, reviews portfolio, money and maintenance, and now and then logs a work order. */
    static ScenarioBuilder propertyManager(Map<String, String> tokens) {
        return scenario("Property manager")
                .feed(listFeeder(Auth.feederFor(tokens, "PROPERTY_MANAGER")).circular())
                .exec(read("Identity: who am I", "/api/identity/me")).exec(think(1, 3))
                .exec(http("Leasing: properties").get("/api/leasing/properties").header("Authorization", AUTH)
                        .check(status().is(200), jsonPath("$[0].id").saveAs("propertyId")))
                .exec(think(1, 3))
                .exec(read("Leasing: units", "/api/leasing/units")).exec(think(1, 3))
                .exec(read("Leasing: leases", "/api/leasing/leases"))
                .exec(read("Leasing: applications", "/api/leasing/applications")).exec(think(1, 3))
                .exec(read("Billing: summary", "/api/billing/summary"))
                .exec(read("Billing: aging", "/api/billing/aging")).exec(think(1, 3))
                .exec(read("Maintenance: work orders", "/api/maintenance/work-orders"))
                .exec(read("Maintenance: stats", "/api/maintenance/stats")).exec(think(1, 3))
                .exec(read("Workflow: notifications", "/api/workflow/notifications"))
                .exec(read("Documents: summary", "/api/documents/summary"))
                .randomSwitch().on(
                        io.gatling.javaapi.core.CoreDsl.percent(10.0).then(
                                exec(http("Maintenance: create work order").post("/api/maintenance/work-orders").header("Authorization", AUTH)
                                        .header("Content-Type", "application/json")
                                        .body(io.gatling.javaapi.core.CoreDsl.StringBody(
                                                "{\"propertyId\":\"#{propertyId}\",\"title\":\"Perf test\",\"description\":\"Created by the performance suite\"}"))
                                        .check(status().in(200, 201)))));
    }

    /** Resident: checks what they owe, their lease and their maintenance requests. */
    static ScenarioBuilder tenant(Map<String, String> tokens) {
        return scenario("Tenant")
                .feed(listFeeder(Auth.feederFor(tokens, "TENANT")).circular())
                .exec(read("Identity: who am I", "/api/identity/me")).exec(think(1, 3))
                .exec(read("Billing: my account", "/api/billing/my")).exec(think(1, 3))
                .exec(read("Leasing: my lease", "/api/leasing/leases/mine")).exec(think(1, 3))
                .exec(read("Maintenance: my requests", "/api/maintenance/work-orders")).exec(think(1, 3))
                .exec(read("Workflow: notifications", "/api/workflow/notifications"))
                .exec(read("Documents: my documents", "/api/documents"));
    }

    /** Accountant: invoices, payments, collections and statements. */
    static ScenarioBuilder accountant(Map<String, String> tokens) {
        return scenario("Accountant")
                .feed(listFeeder(Auth.feederFor(tokens, "ACCOUNTANT")).circular())
                .exec(read("Identity: who am I", "/api/identity/me")).exec(think(1, 2))
                .exec(read("Billing: invoices", "/api/billing/invoices")).exec(think(1, 3))
                .exec(read("Billing: payments", "/api/billing/payments"))
                .exec(read("Billing: collections", "/api/billing/collections")).exec(think(1, 3))
                .exec(read("Billing: statements", "/api/billing/statements"))
                .exec(read("Billing: tax summary", "/api/billing/tax/summary"));
    }

    /** Prospective tenant: browses the public listings, no account, no token. */
    static ScenarioBuilder prospect() {
        return scenario("Prospect (public)")
                .exec(http("Public: listings").get("/api/leasing/public/listings").check(status().is(200), jsonPath("$[0].id").saveAs("listingId")))
                .exec(think(1, 3))
                .exec(http("Public: one listing").get("/api/leasing/public/listings/#{listingId}").check(status().is(200)));
    }
}
