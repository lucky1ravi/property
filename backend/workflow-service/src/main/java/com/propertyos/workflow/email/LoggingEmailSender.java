package com.propertyos.workflow.email;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The default: nothing leaves the machine. The email is only logged, so a local run needs no mail service. */
public class LoggingEmailSender implements EmailSender {
    private static final Logger log = LoggerFactory.getLogger(LoggingEmailSender.class);

    @Override
    public String name() {
        return "simulated";
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        log.info("Simulated email to {} with subject \"{}\" ({} characters). Set EMAIL_PROVIDER=ses to send it.", to, subject, html.length());
    }
}
