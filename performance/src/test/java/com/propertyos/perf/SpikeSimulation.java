package com.propertyos.perf;

import io.gatling.javaapi.core.ClosedInjectionStep;

import static io.gatling.javaapi.core.CoreDsl.constantConcurrentUsers;
import static io.gatling.javaapi.core.CoreDsl.rampConcurrentUsers;

/**
 * A sudden surge (for example a listing shared widely): normal load, a jump to five times as many users within seconds, then back.
 * Checks the platform stays up and recovers; errors above 5% fail the run.
 */
public class SpikeSimulation extends PerfSimulation {
    private static ClosedInjectionStep[] profile(int n) {
        int spike = n * 5;
        return new ClosedInjectionStep[]{constantConcurrentUsers(n).during(20), rampConcurrentUsers(n).to(spike).during(5),
                constantConcurrentUsers(spike).during(Math.max(20, Config.HOLD_SECONDS / 2)), rampConcurrentUsers(spike).to(n).during(5),
                constantConcurrentUsers(n).during(30)};
    }

    public SpikeSimulation() {
        int n = Config.USERS;
        setUp(managers().injectClosed(profile(share(n, MANAGERS))), tenants().injectClosed(profile(share(n, TENANTS))),
                accountants().injectClosed(profile(share(n, ACCOUNTANTS))), prospects().injectClosed(profile(share(n, PROSPECTS))))
                .protocols(protocol)
                .assertions(serviceLevels(Config.P95_MS * 4, 5.0));
    }
}
