package com.propertyos.perf;

import io.gatling.javaapi.core.ClosedInjectionStep;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

/**
 * Steady moderate load for a long time (set {@code holdSeconds}, for example 3600) to expose memory leaks, connection exhaustion and slow degradation.
 * Compare the first and last quarter of the response-time chart in the report.
 */
public class SoakSimulation extends PerfSimulation {
    private static ClosedInjectionStep[] profile(int n) {
        return new ClosedInjectionStep[]{rampConcurrentUsers(0).to(n).during(Config.RAMP_SECONDS), constantConcurrentUsers(n).during(Config.HOLD_SECONDS)};
    }

    public SoakSimulation() {
        int n = Config.USERS;
        setUp(managers().injectClosed(profile(share(n, MANAGERS))), tenants().injectClosed(profile(share(n, TENANTS))),
                accountants().injectClosed(profile(share(n, ACCOUNTANTS))), prospects().injectClosed(profile(share(n, PROSPECTS))))
                .protocols(protocol)
                .assertions(serviceLevels(Config.P95_MS, Config.MAX_ERROR_PERCENT));
    }
}
