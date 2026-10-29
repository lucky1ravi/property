package com.propertyos.common.observability;

import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.resilience.Resilience;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Shows the circuit state for every service this one has called, on {@code /actuator/health/downstream}.
 * It is informational and is not part of the readiness group: a failing neighbour must not take this instance out of rotation, or one outage would spread to all services.
 */
@Component("downstream")
public class DownstreamHealth implements HealthIndicator {
    private final Resilience resilience;

    public DownstreamHealth(Resilience resilience) {
        this.resilience = resilience;
    }

    @Override
    public Health health() {
        Health.Builder b = Health.up();
        Arrays.stream(ServiceClient.Service.values()).forEach(s -> b.withDetail(s.name().toLowerCase(), resilience.state(s.name())));
        return b.build();
    }
}
