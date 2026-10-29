package com.propertyos.billing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Billing service: charges and invoices, payments and autopay, collections, owner statements, security deposits and tax reporting (1099).
 */
@SpringBootApplication(scanBasePackages = "com.propertyos")
public class BillingApplication {
    /**
     * Starts the service in UTC.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(BillingApplication.class, args);
    }
}
