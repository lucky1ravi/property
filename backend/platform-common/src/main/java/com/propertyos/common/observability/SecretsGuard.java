package com.propertyos.common.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start a production service (demo mode off) that still uses the built-in development secrets, and warns in demo mode.
 * A forgotten default signing key would let anyone mint valid tokens.
 */
@Component
public class SecretsGuard {
    private static final Logger log = LoggerFactory.getLogger(SecretsGuard.class);
    private final boolean demo;
    private final String jwtSecret;
    private final String internalToken;

    public SecretsGuard(@Value("${propertyos.demo.enabled:true}") boolean demo,
                        @Value("${propertyos.jwt.secret}") String jwtSecret,
                        @Value("${propertyos.internal-token}") String internalToken) {
        this.demo = demo;
        this.jwtSecret = jwtSecret;
        this.internalToken = internalToken;
    }

    /**
     * Lists the secrets that are still development defaults or too short.
     */
    List<String> weakSecrets() {
        List<String> weak = new ArrayList<>();
        if (jwtSecret.startsWith("dev-only") || jwtSecret.length() < 32) weak.add("JWT_SECRET");
        if (internalToken.startsWith("dev-") || internalToken.length() < 16) weak.add("INTERNAL_TOKEN");
        return weak;
    }

    /**
     * Checks the secrets once the application is up.
     *
     * @throws IllegalStateException in production when a secret is weak
     */
    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        List<String> weak = weakSecrets();
        if (weak.isEmpty()) return;
        if (!demo) throw new IllegalStateException("Refusing to run with development secrets outside demo mode: set " + String.join(", ", weak));
        log.warn("Development secrets in use ({}). Fine for a demo; set real values before production.", String.join(", ", weak));
    }
}
