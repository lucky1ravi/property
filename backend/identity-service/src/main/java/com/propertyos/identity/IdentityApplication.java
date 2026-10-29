package com.propertyos.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Identity service: users, companies, roles and permissions, plans and feature entitlements, per-company configuration, and sign-in. Issues the tokens every other service trusts.
 */
@SpringBootApplication(scanBasePackages = "com.propertyos")
public class IdentityApplication {
    /**
     * Starts the service. The JVM default time zone is set to UTC so stored timestamps are unambiguous.
     *
     * @param args command-line arguments
     */
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IdentityApplication.class, args);
    }
}
