package com.propertyos.workflow.email;

import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;

/** Amazon SES. Requests are signed with the task role on AWS, so no keys are configured. Until the account leaves the SES sandbox, both addresses must be verified. */
public class SesEmailSender implements EmailSender {
    private final SesV2Client ses;
    private final String from;

    public SesEmailSender(SesV2Client ses, String from) {
        this.ses = ses;
        this.from = from;
    }

    @Override
    public String name() {
        return "ses";
    }

    @Override
    public void send(String to, String subject, String html, String text) {
        ses.sendEmail(SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(to).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(utf8(subject))
                        .body(Body.builder().html(utf8(html)).text(utf8(text)).build())
                        .build()).build())
                .build());
    }

    private static Content utf8(String s) {
        return Content.builder().charset("UTF-8").data(s).build();
    }
}
