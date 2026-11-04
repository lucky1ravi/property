package com.propertyos.workflow.email;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplatesTest {
    private static final String REPLY = "Hi Tessa, thanks for your interest.\n\nIt's available now at $2250 per month.\nWould you like to tour it this week?";

    @Test
    @DisplayName("the reply email carries the brand, the company, the message and a subject naming the home")
    void branded() {
        EmailTemplates.Mail m = EmailTemplates.leadReply("Summit Ridge Property Group", "Tessa Nguyen", "2-bedroom at Riverside Commons", REPLY, null);
        assertThat(m.subject()).isEqualTo("Re: your enquiry about 2-bedroom at Riverside Commons");
        assertThat(m.html()).contains("Property", "OS", EmailTemplates.BRAND, "Summit Ridge Property Group", "$2250 per month", "Just reply to this email");
        assertThat(m.html()).doesNotContain("Test mode");
        assertThat(m.text()).contains("Summit Ridge Property Group", REPLY, "Sent with PropertyOS");
    }

    @Test
    @DisplayName("paragraphs and line breaks of the reply are kept")
    void layoutOfReply() {
        String html = EmailTemplates.leadReply("Co", "T", "Home", REPLY, null).html();
        assertThat(html).contains("<p style=\"margin:0 0 14px\">Hi Tessa, thanks for your interest.</p>");
        assertThat(html).contains("It&#39;s available now at $2250 per month.<br>Would you like to tour it this week?");
    }

    @Test
    @DisplayName("text a person typed cannot add markup")
    void escaped() {
        EmailTemplates.Mail m = EmailTemplates.leadReply("<b>Co</b>", "T", "Home <script>", "<img src=x onerror=alert(1)> & more", null);
        assertThat(m.html()).doesNotContain("<img", "<script>", "<b>Co</b>");
        assertThat(m.html()).contains("&lt;img src=x onerror=alert(1)&gt; &amp; more");
    }

    @Test
    @DisplayName("an email redirected to a test address says who it was meant for")
    void testMode() {
        EmailTemplates.Mail m = EmailTemplates.leadReply("Co", "T", "Home", "Hello", "tessa@example.com");
        assertThat(m.html()).contains("Test mode: this was meant for tessa@example.com.");
        assertThat(m.text()).startsWith("[Test mode: this was meant for tessa@example.com]");
    }

    @Test
    @DisplayName("a missing home name still gives a sensible subject")
    void noListing() {
        assertThat(EmailTemplates.leadReply("Co", "T", "", "Hello", null).subject()).isEqualTo("Re: your enquiry about a home");
    }
}
