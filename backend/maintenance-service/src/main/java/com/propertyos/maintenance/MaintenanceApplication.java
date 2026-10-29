package com.propertyos.maintenance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Maintenance service: work orders with triage and SLA tracking, vendors, recurring tasks and vendor insurance alerts.
 */
@SpringBootApplication(scanBasePackages = "com.propertyos")
public class MaintenanceApplication {
    /**
     * Starts the service in UTC.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(MaintenanceApplication.class, args);
    }
}
