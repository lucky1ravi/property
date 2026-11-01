package com.propertyos.common.config;

import com.propertyos.common.events.DomainEvent;
import com.propertyos.common.events.EventHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * When a company's settings change, every service drops what it remembered about them, so the change applies everywhere within a
 * couple of seconds instead of whenever each service's cache happens to expire.
 */
@Component
public class ConfigCacheInvalidator implements EventHandler {
    private final ConfigClient config;
    private final String service;

    public ConfigCacheInvalidator(ConfigClient config, @Value("${spring.application.name:service}") String service) {
        this.config = config;
        this.service = service;
    }

    /** Each service keeps its own place in the event log, so each hears about every change. */
    @Override
    public String subscriber() {
        return "config-cache:" + service;
    }

    @Override
    public Set<String> eventTypes() {
        return Set.of("ConfigurationChanged");
    }

    @Override
    public void handle(DomainEvent event) {
        if (event.orgId() != null) config.invalidate(event.orgId());
    }
}
