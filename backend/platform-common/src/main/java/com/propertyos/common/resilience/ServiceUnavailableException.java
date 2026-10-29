package com.propertyos.common.resilience;

import org.springframework.web.client.ResourceAccessException;

/**
 * Raised instead of calling a service whose circuit is open or whose capacity is used up.
 * It is a {@link org.springframework.web.client.RestClientException}, so callers that already degrade gracefully when a service is down handle it the same way.
 */
public class ServiceUnavailableException extends ResourceAccessException {
    public ServiceUnavailableException(String service, String reason) {
        super(service + " is temporarily unavailable (" + reason + ")");
    }
}
