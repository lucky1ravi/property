package com.propertyos.workflow.email;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The branded layout every PropertyOS email uses: a dark green header with the product name, the company the message is from, the message itself (a heading,
 * paragraphs, a few labelled facts and one button) and a quiet footer. Tables and inline styles only, because mail programs ignore stylesheets. Every value is
 * escaped, so text typed by a person cannot add markup, and a button only ever links to a web address.
 */
public final class EmailTemplates {
    static final String BRAND = "#0F3D3A";
    static final String ACCENT = "#2E7D5B";
    private static final String INK = "#1D2524";
    private static final String MUTED = "#66726F";
    private static final String PAPER = "#F5F7F6";
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a", Locale.US);
    private static final ZoneId CENTRAL = ZoneId.of("America/Chicago");

    private EmailTemplates() {
    }

    /** A rendered email: the subject and the two bodies. */
    public record Mail(String subject, String html, String text) {
    }

    /** One labelled fact in the box under the paragraphs, such as "Email: ada@example.com". */
    public record Fact(String label, String value) {
    }

    /** What goes into one email. {@code intendedFor} is the real recipient when the email is being redirected to a test address, otherwise null. */
    public record Content(String company, String heading, List<String> paragraphs, List<Fact> facts, String buttonLabel, String buttonUrl, String closing, String intendedFor) {
        public Content(String company, String heading, List<String> paragraphs, String intendedFor) {
            this(company, heading, paragraphs, List.of(), null, null, null, intendedFor);
        }
    }

    // ---------------------------------------------------------------------------------------------- the general message

    /** Builds the email from its parts; the subject is the heading unless one is given. */
    public static Mail render(String subject, Content c) {
        String banner = c.intendedFor() == null ? "" : "<tr><td style=\"background:#FCF6EA;border-bottom:1px solid #F8EBD3;padding:10px 32px;font-size:12px;color:#8A5A13\">"
                + "Test mode: this was meant for " + esc(c.intendedFor()) + ".</td></tr>";
        StringBuilder body = new StringBuilder();
        body.append("<p style=\"margin:0 0 6px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:").append(MUTED).append("\">").append(esc(c.company())).append("</p>")
                .append("<h1 style=\"margin:0 0 20px;font-size:20px;line-height:28px;color:").append(INK).append("\">").append(esc(c.heading())).append("</h1>")
                .append("<div style=\"font-size:15px;line-height:24px;color:").append(INK).append("\">");
        for (String p : c.paragraphs()) body.append(paragraphs(p));
        body.append("</div>");
        if (!c.facts().isEmpty()) {
            body.append("<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"margin:8px 0 18px;background:").append(PAPER)
                    .append(";border:1px solid #E8ECEA;border-radius:6px\">");
            for (Fact f : c.facts())
                body.append("<tr><td style=\"padding:9px 14px;font-size:13px;color:").append(MUTED).append(";width:38%;vertical-align:top\">").append(esc(f.label()))
                        .append("</td><td style=\"padding:9px 14px;font-size:14px;font-weight:600;color:").append(INK).append(";word-break:break-word\">").append(esc(f.value())).append("</td></tr>");
            body.append("</table>");
        }
        if (c.buttonLabel() != null && safeUrl(c.buttonUrl()))
            body.append("<p style=\"margin:20px 0 6px\"><a href=\"").append(esc(c.buttonUrl())).append("\" style=\"display:inline-block;background:").append(BRAND)
                    .append(";color:#ffffff;text-decoration:none;font-weight:700;font-size:15px;padding:12px 22px;border-radius:6px\">").append(esc(c.buttonLabel())).append("</a></p>");
        if (c.closing() != null && !c.closing().isBlank())
            body.append("<p style=\"margin:20px 0 0;font-size:15px;line-height:24px;color:").append(INK).append("\">").append(esc(c.closing())).append("</p>");

        StringBuilder text = new StringBuilder();
        if (c.intendedFor() != null) text.append("[Test mode: this was meant for ").append(c.intendedFor()).append("]\n\n");
        text.append(c.company()).append("\n\n").append(c.heading()).append("\n\n");
        for (String p : c.paragraphs()) text.append(p.strip()).append("\n\n");
        for (Fact f : c.facts()) text.append(f.label()).append(": ").append(f.value()).append("\n");
        if (!c.facts().isEmpty()) text.append("\n");
        if (c.buttonLabel() != null && safeUrl(c.buttonUrl())) text.append(c.buttonLabel()).append(": ").append(c.buttonUrl()).append("\n\n");
        if (c.closing() != null && !c.closing().isBlank()) text.append(c.closing()).append("\n\n");
        text.append("Sent with PropertyOS");
        return new Mail(subject, layout(subject, banner, body.toString(), "Sent on behalf of " + c.company()), text.toString());
    }

    /** The email for a notification a person already sees in the app: its title, its text, and a button to open it. */
    public static Mail notification(String company, String title, String body, String linkUrl, String intendedFor) {
        return render(title, new Content(company, title, List.of(body == null ? "" : body), List.of(), linkUrl == null ? null : "Open in PropertyOS", linkUrl, null, intendedFor));
    }

    // ---------------------------------------------------------------------------------------------- accounts

    /** A new account: who it is for, the one-time password, and where to sign in. The password is replaced at first sign-in. */
    public static Mail welcome(String company, String name, String email, String temporaryPassword, String signInUrl, String intendedFor) {
        return render("Welcome to " + company + " on PropertyOS", new Content(company, "Welcome, " + firstName(name),
                List.of("An account was made for you on PropertyOS. Use the details below to sign in. You will be asked to choose your own password the first time."),
                List.of(new Fact("Email", email), new Fact("Temporary password", temporaryPassword)), "Sign in", signInUrl,
                "For your security, do not forward this email. The temporary password stops working once you choose your own.", intendedFor));
    }

    /** An administrator reset the password: the new one-time password. */
    public static Mail passwordReset(String company, String name, String email, String temporaryPassword, String signInUrl, String intendedFor) {
        return render("Your PropertyOS password was reset", new Content(company, "Your password was reset",
                List.of("Hi " + firstName(name) + ", an administrator reset the password for your PropertyOS account. Sign in with the temporary password below and choose a new one."),
                List.of(new Fact("Email", email), new Fact("Temporary password", temporaryPassword)), "Sign in", signInUrl,
                "If you did not expect this, tell your administrator.", intendedFor));
    }

    /** A security notice after someone chose a new password. */
    public static Mail passwordChanged(String company, String name, String signInUrl, String intendedFor) {
        return render("Your PropertyOS password was changed", new Content(company, "Your password was changed",
                List.of("Hi " + firstName(name) + ", the password for your PropertyOS account was just changed."), List.of(), "Sign in", signInUrl,
                "If this was not you, ask your administrator to reset your password straight away.", intendedFor));
    }

    // ---------------------------------------------------------------------------------------------- prospects and applicants

    /**
     * The reply to a prospect's enquiry.
     *
     * @param company      the company that wrote the reply
     * @param name         the prospect, for the greeting line of the plain-text version
     * @param listingTitle the home they asked about
     * @param reply        the message staff approved, which already greets the prospect by name
     * @param intendedFor  the prospect's real address when the email is being redirected, otherwise null
     */
    public static Mail leadReply(String company, String name, String listingTitle, String reply, String intendedFor) {
        String subject = "Re: your enquiry about " + (listingTitle == null || listingTitle.isBlank() ? "a home" : listingTitle);
        return render(subject, new Content(company, subject, List.of(reply == null ? "" : reply), List.of(), null, null,
                "Just reply to this email and " + company + " will get back to you.", intendedFor));
    }

    /** We got the application. */
    public static Mail applicationReceived(String company, String name, String home, String intendedFor) {
        return render("We received your application", new Content(company, "We received your application",
                List.of("Hi " + firstName(name) + ", thank you for applying for " + (home == null || home.isBlank() ? "the home" : home) + ". Screening starts automatically, and we will email you as soon as there is a decision."),
                List.of(), null, null, "If you have not yet uploaded your documents, please do so now so screening is not held up.", intendedFor));
    }

    /** The decision on an application. A refusal says where to find the applicant's rights, as the law asks. */
    public static Mail applicationDecision(String company, String name, String home, boolean approved, String intendedFor) {
        String h = home == null || home.isBlank() ? "the home" : home;
        if (approved) return render("Good news about your application", new Content(company, "Your application was approved",
                List.of("Hi " + firstName(name) + ", " + company + " approved your application for " + h + ". The next step is your lease, which we will send you to review and sign."),
                List.of(), null, null, "We will be in touch shortly with the details.", intendedFor));
        return render("An update on your application", new Content(company, "An update on your application",
                List.of("Hi " + firstName(name) + ", thank you for applying for " + h + ". " + company + " is not able to approve your application at this time.",
                        "If the decision relied in part on a consumer report, you have the right to a free copy of that report from the agency that supplied it within 60 days, and to dispute anything in it that is wrong. The agency did not make this decision and cannot explain it."),
                List.of(), null, null, "You are welcome to ask " + company + " about other homes.", intendedFor));
    }

    /** A tour was asked for (or confirmed): the home and the time. */
    public static Mail tour(String company, String name, String home, OffsetDateTime at, boolean confirmed, String intendedFor) {
        String when = at == null ? "" : at.atZoneSameInstant(CENTRAL).format(WHEN) + " (Central time)";
        List<Fact> facts = new ArrayList<>();
        facts.add(new Fact("Home", home == null || home.isBlank() ? "The home" : home));
        if (!when.isBlank()) facts.add(new Fact("When", when));
        return confirmed
                ? render("Your tour is confirmed", new Content(company, "Your tour is confirmed", List.of("Hi " + firstName(name) + ", " + company + " confirmed your tour."), facts, null, null,
                        "Need to change the time? Just reply to this email.", intendedFor))
                : render("We got your tour request", new Content(company, "We got your tour request", List.of("Hi " + firstName(name) + ", thank you for asking to see a home. " + company + " will confirm the time by email."), facts, null, null,
                        "Need to change anything? Just reply to this email.", intendedFor));
    }

    // ---------------------------------------------------------------------------------------------- layout

    private static String layout(String title, String banner, String body, String footer) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>" + esc(title) + "</title></head>"
                + "<body style=\"margin:0;padding:0;background:" + PAPER + ";font-family:'Public Sans',-apple-system,'Segoe UI',Helvetica,Arial,sans-serif\">"
                + "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" style=\"background:" + PAPER + ";padding:24px 12px\"><tr><td align=\"center\">"
                + "<table role=\"presentation\" width=\"600\" cellpadding=\"0\" cellspacing=\"0\" style=\"width:100%;max-width:600px;background:#ffffff;border:1px solid #E8ECEA;border-radius:8px;overflow:hidden\">"
                + "<tr><td style=\"background:" + BRAND + ";padding:20px 32px\"><span style=\"font-size:20px;font-weight:800;letter-spacing:-.01em;color:#ffffff\">Property</span>"
                + "<span style=\"font-size:20px;font-weight:800;letter-spacing:-.01em;color:#8FD3B4\">OS</span></td></tr>"
                + "<tr><td style=\"height:4px;background:" + ACCENT + ";font-size:0;line-height:0\">&nbsp;</td></tr>"
                + banner
                + "<tr><td style=\"padding:28px 32px 32px\">" + body + "</td></tr>"
                + "<tr><td style=\"background:" + PAPER + ";border-top:1px solid #E8ECEA;padding:16px 32px;font-size:12px;line-height:18px;color:" + MUTED + "\">"
                + esc(footer) + ". Powered by PropertyOS.</td></tr>"
                + "</table></td></tr></table></body></html>";
    }

    /** Blank lines start a new paragraph and single line breaks stay line breaks. */
    static String paragraphs(String text) {
        StringBuilder out = new StringBuilder();
        for (String p : (text == null ? "" : text.strip()).split("\\R{2,}"))
            if (!p.isBlank()) out.append("<p style=\"margin:0 0 14px\">").append(esc(p.strip()).replaceAll("\\R", "<br>")).append("</p>");
        return out.toString();
    }

    static String firstName(String name) {
        String n = name == null ? "" : name.strip();
        return n.isEmpty() ? "there" : n.split("\\s+")[0];
    }

    /** A button only links to a web address; anything else (a script, a data address) is left out. */
    static boolean safeUrl(String url) {
        return url != null && (url.startsWith("https://") || url.startsWith("http://"));
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
