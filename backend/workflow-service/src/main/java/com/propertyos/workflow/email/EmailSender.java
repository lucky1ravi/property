package com.propertyos.workflow.email;

/** Sends one email. {@link SesEmailSender} uses Amazon SES; {@link LoggingEmailSender} only logs, for local runs. */
public interface EmailSender {
    /** Provider name shown in the delivery log, such as {@code ses} or {@code simulated}. */
    String name();

    /** Sends the message, or throws when the provider refuses it or cannot be reached. */
    void send(String to, String subject, String html, String text) throws Exception;
}
