package com.propertyos.perf;

import io.gatling.javaapi.core.ClosedInjectionStep;

import static io.gatling.javaapi.core.CoreDsl.incrementConcurrentUsers;

/**
 * Finds the limit: starts at {@code users} and adds the same number every step, six times. Read the report to see at which step
 * response times climb or errors begin. The run only fails when errors exceed 10%, because hitting a limit is the point.
 */
public class StressSimulation extends PerfSimulation {
    private static ClosedInjectionStep[] profile(int start) {
        return new ClosedInjectionStep[]{incrementConcurrentUsers(start).times(6).eachLevelLasting(Math.max(15, Config.HOLD_SECONDS / 3))
                .separatedByRampsLasting(10).startingFrom(start)};
    }

    public StressSimulation() {
        int n = Config.USERS;
        setUp(managers().injectClosed(profile(share(n, MANAGERS))), tenants().injectClosed(profile(share(n, TENANTS))),
                accountants().injectClosed(profile(share(n, ACCOUNTANTS))), prospects().injectClosed(profile(share(n, PROSPECTS))))
                .protocols(protocol)
                .assertions(serviceLevels(10_000, 10.0));
    }
}
