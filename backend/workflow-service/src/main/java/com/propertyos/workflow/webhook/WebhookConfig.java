package com.propertyos.workflow.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the address guard. Production is the setting where demo mode is off: there, only https is accepted. The private-network switch exists so automated
 * tests can deliver to a receiver on the same machine; it has no effect in production and logs a warning wherever it is on.
 */
@Configuration
public class WebhookConfig {
    private static final Logger log = LoggerFactory.getLogger(WebhookConfig.class);

    @Bean
    public UrlGuard urlGuard(@Value("${propertyos.demo.enabled:true}") boolean demo,
                             @Value("${propertyos.webhooks.allow-private-network:false}") boolean allowPrivateNetwork) {
        boolean requireHttps = !demo;
        if (allowPrivateNetwork) log.warn("Webhooks may call private and loopback addresses (propertyos.webhooks.allow-private-network). Use this for tests only.{}",
                requireHttps ? " It is ignored because demo mode is off." : "");
        return new UrlGuard(requireHttps, allowPrivateNetwork);
    }
}
