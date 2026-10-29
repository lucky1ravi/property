package com.propertyos.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Document management: files, versions, sharing, retention, requirements checklists and e-signatures. */
@SpringBootApplication(scanBasePackages = "com.propertyos")
public class DocumentApplication {
    public static void main(String[] args) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"));
        System.setProperty("java.awt.headless", "true");
        SpringApplication.run(DocumentApplication.class, args);
    }
}
