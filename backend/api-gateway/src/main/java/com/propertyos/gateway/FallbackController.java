package com.propertyos.gateway;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Answers for a service whose circuit is open or that could not be reached: a fast, readable 503 that tells the caller when to retry.
 */
@RestController
public class FallbackController {
    /**
     * Returns a problem response for the named service.
     *
     * @param service the service that is unavailable
     */
    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> unavailable(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .header("Retry-After", "10")
                .body(Map.of("status", 503, "title", "Service Unavailable",
                        "detail", "The " + service + " service is temporarily unavailable. Please retry shortly."));
    }
}
