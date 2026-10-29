package com.propertyos.perf;

import io.gatling.javaapi.core.ClosedInjectionStep;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

/**
 * Expected daily peak: {@code users} people at once for {@code holdSeconds}, after a ramp-up.
 * Must meet the service levels ({@code p95Ms}, {@code maxErrorPercent}) or the run fails.
 */
public class LoadSimulation extends PerfSimulation {
    private static ClosedInjectionStep[] profile(int n) {
        return new ClosedInjectionStep[]{rampConcurrentUsers(0).to(n).during(Config.RAMP_SECONDS), constantConcurrentUsers(n).during(Config.HOLD_SECONDS)};
    }

    public LoadSimulation() {
        int n = Config.USERS;
        setUp(managers().injectClosed(profile(share(n, MANAGERS))), tenants().injectClosed(profile(share(n, TENANTS))),
                accountants().injectClosed(profile(share(n, ACCOUNTANTS))), prospects().injectClosed(profile(share(n, PROSPECTS))))
                .protocols(protocol)
                .assertions(serviceLevels(Config.P95_MS, Config.MAX_ERROR_PERCENT));
    }
}
