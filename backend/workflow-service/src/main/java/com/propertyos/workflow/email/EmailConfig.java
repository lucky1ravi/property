package com.propertyos.workflow.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sesv2.SesV2Client;

/**
 * Chooses how email leaves the platform. {@code propertyos.email.provider} (env {@code EMAIL_PROVIDER}) is {@code none} by default, which only logs, or
 * {@code ses}, which needs the sender address in {@code EMAIL_FROM}. While {@code EMAIL_OVERRIDE_TO} is set, every email goes to that one address
 * instead of its real recipient (the message says who it was meant for), so nothing reaches a real prospect until it is cleared.
 */
@Configuration
public class EmailConfig {
    private static final Logger log = LoggerFactory.getLogger(EmailConfig.class);

    @Bean
    public EmailSender emailSender(@Value("${propertyos.email.provider:none}") String provider, @Value("${propertyos.email.from:}") String from,
                                   @Value("${propertyos.email.region:}") String region) {
        if (!"ses".equalsIgnoreCase(provider)) return new LoggingEmailSender();
        if (from.isBlank()) throw new IllegalStateException("EMAIL_PROVIDER=ses needs EMAIL_FROM, a sender address verified in Amazon SES.");
        // SES identities belong to one region. EMAIL_REGION lets a laptop (whose AWS_REGION is for Bedrock) use the region where the sender is verified.
        SesV2Client ses = region.isBlank() ? SesV2Client.create() : SesV2Client.builder().region(Region.of(region.trim())).build();
        log.info("Email: Amazon SES{}, from {}", region.isBlank() ? "" : " in " + region.trim(), from);
        return new SesEmailSender(ses, from);
    }
}
