package com.propertyos.perf;

import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;

/** One user through every journey: proves the scripts and the platform work before heavier tests. Runs in seconds. */
public class SmokeSimulation extends PerfSimulation {
    public SmokeSimulation() {
        setUp(managers().injectOpen(atOnceUsers(1)), tenants().injectOpen(atOnceUsers(1)),
                accountants().injectOpen(atOnceUsers(1)), prospects().injectOpen(atOnceUsers(1)))
                .protocols(protocol)
                .assertions(serviceLevels(2000, 0.0));
    }
}
