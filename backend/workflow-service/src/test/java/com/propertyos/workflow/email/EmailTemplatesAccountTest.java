package com.propertyos.workflow.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesAccountTest {
    @Test
    @DisplayName("a welcome email names the account, shows the temporary password and links to sign in")
    void welcome() {
        EmailTemplates.Mail m = EmailTemplates.welcome("Summit Ridge", "Priya Patel", "priya@example.com", "Ab3-xyz-Q9!k2m", "https://app.example.com/signin", null);
        assertThat(m.subject()).isEqualTo("Welcome to Summit Ridge on PropertyOS");
        assertThat(m.html()).contains("Welcome, Priya", "priya@example.com", "Ab3-xyz-Q9!k2m", "Temporary password", "href=\"https://app.example.com/signin\"", "choose your own password");
        assertThat(m.text()).contains("Email: priya@example.com", "Temporary password: Ab3-xyz-Q9!k2m", "Sign in: https://app.example.com/signin", "Sent with PropertyOS");
    }

    @Test
    @DisplayName("a reset and a changed-password notice say what happened and what to do if it was not expected")
    void resetAndChanged() {
        EmailTemplates.Mail reset = EmailTemplates.passwordReset("Summit Ridge", "Priya Patel", "priya@example.com", "Temp-Pass-1", "https://app.example.com/signin", null);
        assertThat(reset.subject()).isEqualTo("Your PropertyOS password was reset");
        assertThat(reset.html()).contains("Temp-Pass-1", "reset the password", "If you did not expect this");
        EmailTemplates.Mail changed = EmailTemplates.passwordChanged("Summit Ridge", "Priya Patel", "https://app.example.com/signin", null);
        assertThat(changed.subject()).isEqualTo("Your PropertyOS password was changed");
        assertThat(changed.html()).contains("was just changed", "If this was not you").doesNotContain("Temporary password");
    }

    @Test
    @DisplayName("a notification email carries the title and text, a button to open it, and nothing a person typed can add markup")
    void notification() {
        EmailTemplates.Mail m = EmailTemplates.notification("Co", "Payment received", "We received <b>$1,800</b> & thank you.", "https://app.example.com/app/payments", null);
        assertThat(m.subject()).isEqualTo("Payment received");
        assertThat(m.html()).contains("Open in PropertyOS", "href=\"https://app.example.com/app/payments\"", "&lt;b&gt;$1,800&lt;/b&gt; &amp; thank you.").doesNotContain("<b>$1,800");
        assertThat(EmailTemplates.notification("Co", "Hello", "Text", null, null).html()).doesNotContain("Open in PropertyOS");
        assertThat(EmailTemplates.notification("Co", "Hello", "Text", "javascript:alert(1)", null).html()).doesNotContain("javascript:");
    }

    @Test
    @DisplayName("an applicant is told their application arrived, and what was decided, with their rights if it was refused")
    void applicant() {
        assertThat(EmailTemplates.applicationReceived("Co", "Ann Applicant", "Unit 4B", null).html()).contains("Hi Ann", "Unit 4B", "decision");
        EmailTemplates.Mail ok = EmailTemplates.applicationDecision("Co", "Ann Applicant", "Unit 4B", true, null);
        assertThat(ok.subject()).isEqualTo("Good news about your application");
        assertThat(ok.html()).contains("approved your application for Unit 4B", "lease");
        EmailTemplates.Mail no = EmailTemplates.applicationDecision("Co", "Ann Applicant", "Unit 4B", false, null);
        assertThat(no.subject()).isEqualTo("An update on your application");
        assertThat(no.html()).contains("not able to approve", "free copy", "dispute").doesNotContain("approved your application");
    }

    @Test
    @DisplayName("a tour email names the home and the time in Central time, for a request and for a confirmation")
    void tour() {
        OffsetDateTime at = OffsetDateTime.parse("2026-11-09T20:00:00Z");
        EmailTemplates.Mail asked = EmailTemplates.tour("Co", "Tina Tourist", "Studio 12", at, false, null);
        assertThat(asked.subject()).isEqualTo("We got your tour request");
        assertThat(asked.html()).contains("Studio 12", "Mon, Nov 9 at 2:00 PM (Central time)");
        EmailTemplates.Mail confirmed = EmailTemplates.tour("Co", "Tina Tourist", "Studio 12", at, true, null);
        assertThat(confirmed.subject()).isEqualTo("Your tour is confirmed");
        assertThat(EmailTemplates.tour("Co", "Tina", "Studio 12", null, false, null).html()).doesNotContain("When");
    }

    @Test
    @DisplayName("every email can say who it was meant for while it is redirected to a test address")
    void testMode() {
        for (EmailTemplates.Mail m : List.of(EmailTemplates.welcome("Co", "A", "a@example.com", "pw", "https://x/signin", "a@example.com"),
                EmailTemplates.applicationDecision("Co", "A", "H", true, "a@example.com"), EmailTemplates.notification("Co", "T", "B", null, "a@example.com"))) {
            assertThat(m.html()).contains("Test mode: this was meant for a@example.com.");
            assertThat(m.text()).startsWith("[Test mode: this was meant for a@example.com]");
        }
    }

    @Test
    @DisplayName("a name that is empty still gives a sensible greeting")
    void greeting() {
        assertThat(EmailTemplates.firstName(null)).isEqualTo("there");
        assertThat(EmailTemplates.firstName("  ")).isEqualTo("there");
        assertThat(EmailTemplates.firstName("Maya Patel")).isEqualTo("Maya");
    }
}
