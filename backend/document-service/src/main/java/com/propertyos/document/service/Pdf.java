package com.propertyos.document.service;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal PDF writer for documents the platform generates itself (lease agreements, signature certificates).
 * Letter-size pages, Helvetica, automatic wrapping and page breaks. No external library needed.
 */
public final class Pdf {
    /**
     * Page size in points (US Letter) and the margin.
     */
    private static final int WIDTH = 612, HEIGHT = 792, MARGIN = 60;

    private record Line(String text, boolean bold, int size, int gapBefore) {
    }

    private final List<Line> lines = new ArrayList<>();
    private final String footer;

    public Pdf(String footer) {
        this.footer = footer;
    }

    /**
     * Adds a large bold title line.
     */
    public Pdf title(String t) {
        lines.add(new Line(t, true, 16, 0));
        return this;
    }

    /**
     * Adds a bold heading line.
     */
    public Pdf heading(String t) {
        lines.add(new Line(t, true, 11, 10));
        return this;
    }

    /**
     * Adds wrapped body text; null is ignored.
     */
    public Pdf text(String t) {
        if (t == null) return this;
        for (String para : t.replace("\r", "").split("\n", -1)) {
            if (para.isBlank()) {
                lines.add(new Line("", false, 10, 0));
                continue;
            }
            for (String w : wrap(para, 92)) lines.add(new Line(w, false, 10, 0));
        }
        return this;
    }

    /**
     * Adds a "label: value" line, showing "-" for a blank value.
     */
    public Pdf field(String label, String value) {
        return text(label + ": " + (value == null || value.isBlank() ? "-" : value));
    }

    /**
     * Adds vertical space.
     */
    public Pdf space() {
        lines.add(new Line("", false, 10, 0));
        return this;
    }

    private static List<String> wrap(String s, int max) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            if (cur.length() + word.length() + 1 > max && cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
            while (word.length() > max) {
                out.add(word.substring(0, max));
                word = word.substring(max);
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(word);
        }
        out.add(cur.toString());
        return out;
    }

    private static String esc(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '‘', '’' -> b.append('\'');
                case '“', '”' -> b.append('"');
                case '–', '—' -> b.append('-');
                case '•', '·' -> b.append('*');
                case '…' -> b.append("...");
                case '(', ')', '\\' -> b.append('\\').append(c);
                default -> b.append(c < 32 || c > 255 ? '?' : c);
            }
        }
        return b.toString();
    }

    /**
     * Lays the content out on pages and returns the PDF file.
     */
    public byte[] bytes() {
        // Lay out into pages.
        List<StringBuilder> pages = new ArrayList<>();
        StringBuilder page = null;
        int y = 0;
        for (Line l : lines) {
            int lead = (int) (l.size * 1.45) + l.gapBefore;
            if (page == null || y - lead < MARGIN + 30) {
                page = new StringBuilder();
                pages.add(page);
                y = HEIGHT - MARGIN;
            }
            y -= lead;
            if (!l.text.isEmpty())
                page.append("BT /").append(l.bold ? "F2" : "F1").append(' ').append(l.size).append(" Tf ")
                        .append(MARGIN).append(' ').append(y).append(" Td (").append(esc(l.text)).append(") Tj ET\n");
        }
        if (pages.isEmpty()) pages.add(new StringBuilder());
        for (int i = 0; i < pages.size(); i++) {
            String f = (footer == null ? "" : footer + "   ") + "Page " + (i + 1) + " of " + pages.size();
            pages.get(i).append("0.45 g BT /F1 8 Tf ").append(MARGIN).append(" 36 Td (").append(esc(f)).append(") Tj ET 0 g\n");
        }

        // Objects: 1 catalog, 2 pages, 3 font, 4 bold font, then (page, content) pairs.
        List<byte[]> objects = new ArrayList<>();
        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pages.size(); i++) kids.append(5 + i * 2).append(" 0 R ");
        objects.add("<< /Type /Catalog /Pages 2 0 R >>".getBytes(StandardCharsets.ISO_8859_1));
        objects.add(("<< /Type /Pages /Kids [" + kids + "] /Count " + pages.size() + " >>").getBytes(StandardCharsets.ISO_8859_1));
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
        objects.add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>".getBytes(StandardCharsets.ISO_8859_1));
        for (int i = 0; i < pages.size(); i++) {
            int contentObj = 6 + i * 2;
            objects.add(("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + WIDTH + " " + HEIGHT + "] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents " + contentObj + " 0 R >>")
                    .getBytes(StandardCharsets.ISO_8859_1));
            byte[] stream = pages.get(i).toString().getBytes(StandardCharsets.ISO_8859_1);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(("<< /Length " + stream.length + " >>\nstream\n").getBytes(StandardCharsets.ISO_8859_1));
            o.writeBytes(stream);
            o.writeBytes("\nendstream".getBytes(StandardCharsets.ISO_8859_1));
            objects.add(o.toByteArray());
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("%PDF-1.4\n%âãÏÓ\n".getBytes(StandardCharsets.ISO_8859_1));
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < objects.size(); i++) {
            offsets.add(out.size());
            out.writeBytes(((i + 1) + " 0 obj\n").getBytes(StandardCharsets.ISO_8859_1));
            out.writeBytes(objects.get(i));
            out.writeBytes("\nendobj\n".getBytes(StandardCharsets.ISO_8859_1));
        }
        int xref = out.size();
        StringBuilder x = new StringBuilder("xref\n0 ").append(objects.size() + 1).append("\n0000000000 65535 f \n");
        for (int off : offsets) x.append(String.format("%010d 00000 n \n", off));
        x.append("trailer\n<< /Size ").append(objects.size() + 1).append(" /Root 1 0 R >>\nstartxref\n").append(xref).append("\n%%EOF\n");
        out.writeBytes(x.toString().getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }
}
