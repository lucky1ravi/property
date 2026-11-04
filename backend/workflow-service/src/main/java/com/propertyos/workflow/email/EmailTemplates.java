package com.propertyos.workflow.email;

/**
 * The branded layout every PropertyOS email uses: a dark green header with the product name, the company the message is from, the message itself, and a quiet
 * footer. Tables and inline styles only, because mail programs ignore stylesheets. Every value is escaped, so text typed by a person cannot add markup.
 */
public final class EmailTemplates {
    static final String BRAND = "#0F3D3A";
    static final String ACCENT = "#2E7D5B";
    private static final String INK = "#1D2524";
    private static final String MUTED = "#66726F";
    private static final String PAPER = "#F5F7F6";

    private EmailTemplates() {
    }

    /** A rendered email: the subject and the two bodies. */
    public record Mail(String subject, String html, String text) {
    }

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
        String banner = intendedFor == null ? "" : "<tr><td style=\"background:#FCF6EA;border-bottom:1px solid #F8EBD3;padding:10px 32px;font-size:12px;color:#8A5A13\">"
                + "Test mode: this was meant for " + esc(intendedFor) + ".</td></tr>";
        String body = "<p style=\"margin:0 0 6px;font-size:12px;letter-spacing:.06em;text-transform:uppercase;color:" + MUTED + "\">" + esc(company) + "</p>"
                + "<h1 style=\"margin:0 0 20px;font-size:20px;line-height:28px;color:" + INK + "\">" + esc(subject) + "</h1>"
                + "<div style=\"font-size:15px;line-height:24px;color:" + INK + "\">" + paragraphs(reply) + "</div>"
                + "<p style=\"margin:24px 0 0;font-size:15px;line-height:24px;color:" + INK + "\">Just reply to this email and " + esc(company) + " will get back to you.</p>";
        String text = (intendedFor == null ? "" : "[Test mode: this was meant for " + intendedFor + "]\n\n") + company + "\n\n" + reply
                + "\n\nJust reply to this email and " + company + " will get back to you.\n\nSent with PropertyOS";
        return new Mail(subject, layout(subject, banner, body, "Sent on behalf of " + company), text);
    }

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

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
