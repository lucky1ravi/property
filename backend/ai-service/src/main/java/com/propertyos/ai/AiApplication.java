package com.propertyos.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI service: the assistant (with tools and confirm-before-write), AI studio tools, photo checking, broker insights, guardrails and the AI audit.
 */
@SpringBootApplication(scanBasePackages = "com.propertyos")
public class AiApplication {
    /**
     * Starts the service in UTC.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AiApplication.class, args);
    }
}
