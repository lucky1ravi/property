package com.propertyos.document.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.web.ApiException;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.util.*;

/** What may be uploaded: size, extension, and a content check so a renamed executable or web page is refused. */
@Component
public class FileRules {
    private final ConfigClient config;

    public FileRules(ConfigClient config) {
        this.config = config;
    }

    /**
     * Content type for each allowed extension.
     */
    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("pdf", "application/pdf"), Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("heic", "image/heic"),
            Map.entry("doc", "application/msword"), Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xls", "application/vnd.ms-excel"), Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("csv", "text/csv"), Map.entry("txt", "text/plain"));

    /** Never accepted, whatever the configuration says: these can run code in a browser or on a computer. */
    private static final Set<String> BLOCKED = Set.of("exe", "bat", "cmd", "com", "msi", "sh", "ps1", "js", "jar", "vbs", "scr", "html", "htm", "svg", "xhtml", "php", "dll", "app");

    /**
     * The outcome of checking an upload.
     *
     * @param fileName  safe file name
     * @param extension lower-case extension
     * @param mimeType detected content type
     * @param size      size in bytes
     * @param sha256    content hash, used to spot duplicates
     */
    public record Checked(String fileName, String extension, String mimeType, long size, String sha256) {
    }

    /**
     * Returns the largest upload allowed, from company settings (15 MB by default).
     */
    public long maxBytes() {
        return config.number("documents/storage/maxFileMb", "15").longValue() * 1024 * 1024;
    }

    /**
     * Returns the file extensions the company accepts.
     */
    public List<String> allowed() {
        List<String> out = new ArrayList<>();
        JsonNode n = config.at("documents/storage/allowedExtensions");
        if (n.isArray()) n.forEach(x -> out.add(x.asText().toLowerCase().replace(".", "")));
        if (out.isEmpty()) out.addAll(MIME.keySet());
        out.removeIf(BLOCKED::contains);
        return out;
    }

    /**
     * Checks an upload: not empty, within the size limit, an allowed type, and content that matches its extension.
     *
     * @param originalName the uploaded file name
     * @param data         the bytes
     * @throws ApiException (400) with a message saying what to change
     */
    public Checked check(String originalName, byte[] data) {
        if (data == null || data.length == 0) throw ApiException.badRequest("The file is empty.");
        if (data.length > maxBytes())
            throw ApiException.badRequest("The file is " + (data.length / 1024 / 1024) + " MB. The limit is " + (maxBytes() / 1024 / 1024) + " MB.");
        String name = clean(originalName == null ? "file" : originalName);
        String ext = name.contains(".") ? name.substring(name.lastIndexOf('.') + 1).toLowerCase() : "";
        if (BLOCKED.contains(ext) || !allowed().contains(ext))
            throw ApiException.badRequest("." + (ext.isEmpty() ? "(no extension)" : ext) + " files can't be uploaded. Allowed: " + String.join(", ", allowed()) + ".");
        if (!contentMatches(ext, data))
            throw ApiException.badRequest("The file's contents don't match its ." + ext + " extension. Export it again and retry.");
        return new Checked(name, ext, MIME.getOrDefault(ext, "application/octet-stream"), data.length, sha256(data));
    }

    static String clean(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}\"<>|*?:]", "_").trim();
        if (n.length() > 180) {
            String ext = n.contains(".") ? n.substring(n.lastIndexOf('.')) : "";
            n = n.substring(0, 180 - ext.length()) + ext;
        }
        return n.isEmpty() ? "file" : n;
    }

    /** Magic-number check for the binary formats; text formats must not contain markup that a browser would run. */
    static boolean contentMatches(String ext, byte[] d) {
        return switch (ext) {
            case "pdf" -> starts(d, "%PDF");
            case "png" -> d.length > 4 && (d[0] & 0xff) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G';
            case "jpg", "jpeg" -> d.length > 3 && (d[0] & 0xff) == 0xFF && (d[1] & 0xff) == 0xD8;
            case "gif" -> starts(d, "GIF8");
            case "webp" -> starts(d, "RIFF") && d.length > 12 && new String(d, 8, 4).equals("WEBP");
            case "heic" -> d.length > 12 && new String(d, 4, 4).equals("ftyp");
            case "docx", "xlsx" -> d.length > 2 && d[0] == 'P' && d[1] == 'K';
            case "doc", "xls" -> d.length > 4 && (d[0] & 0xff) == 0xD0 && (d[1] & 0xff) == 0xCF;
            case "csv", "txt" -> {
                String head = new String(d, 0, Math.min(d.length, 4096), java.nio.charset.StandardCharsets.UTF_8).toLowerCase();
                yield !head.contains("<script") && !head.contains("<html");
            }
            default -> false;
        };
    }

    private static boolean starts(byte[] d, String s) {
        if (d.length < s.length()) return false;
        for (int i = 0; i < s.length(); i++) if (d[i] != s.charAt(i)) return false;
        return true;
    }

    /**
     * Returns the SHA-256 hash of the bytes as lower-case hex.
     */
    public static String sha256(byte[] d) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(d);
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Tells whether a content type is safe to show inline in the browser.
     */
    public static boolean previewable(String mime) {
        return mime != null && (mime.equals("application/pdf") || (mime.startsWith("image/") && !mime.equals("image/heic")) || mime.equals("text/plain") || mime.equals("text/csv"));
    }
}
