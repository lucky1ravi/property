package com.propertyos.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.web.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileRulesTest {
    private final ObjectMapper json = new ObjectMapper();
    private ConfigClient config;
    private FileRules rules;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(ConfigClient.class);
        Mockito.when(config.number("documents/storage/maxFileMb", "15")).thenReturn(new BigDecimal("1"));
        Mockito.when(config.at("documents/storage/allowedExtensions")).thenReturn(json.createArrayNode());
        rules = new FileRules(config);
    }

    private static byte[] bytes(int... b) {
        byte[] out = new byte[b.length];
        for (int i = 0; i < b.length; i++) out[i] = (byte) b[i];
        return out;
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("an empty or missing file is refused")
    void empty() {
        assertThatThrownBy(() -> rules.check("a.pdf", new byte[0])).isInstanceOf(ApiException.class).hasMessageContaining("empty");
        assertThatThrownBy(() -> rules.check("a.pdf", null)).isInstanceOf(ApiException.class).hasMessageContaining("empty");
    }

    @Test
    @DisplayName("a file over the company's size limit is refused and the limit is named")
    void tooBig() {
        assertThatThrownBy(() -> rules.check("big.pdf", new byte[2 * 1024 * 1024])).isInstanceOf(ApiException.class).hasMessageContaining("limit is 1 MB");
    }

    @Test
    @DisplayName("programs, web pages and files with no extension are refused whatever the settings allow")
    void blocked() {
        Mockito.when(config.at("documents/storage/allowedExtensions")).thenReturn(json.createArrayNode().add("exe").add(".html").add("pdf"));
        assertThat(rules.allowed()).containsExactly("pdf");
        assertThatThrownBy(() -> rules.check("setup.exe", text("MZ"))).hasMessageContaining(".exe files can't be uploaded");
        assertThatThrownBy(() -> rules.check("page.HTML", text("<html>"))).hasMessageContaining(".html files can't be uploaded");
        assertThatThrownBy(() -> rules.check("README", text("hello"))).hasMessageContaining("(no extension)");
        assertThatThrownBy(() -> rules.check("movie.mp4", text("x"))).hasMessageContaining("Allowed:");
    }

    @Test
    @DisplayName("each format must look like what its extension says")
    void contents() {
        assertThat(rules.check("lease.pdf", text("%PDF-1.4 body")).mimeType()).isEqualTo("application/pdf");
        assertThat(rules.check("p.png", bytes(0x89, 'P', 'N', 'G', 0x0d, 0x0a)).mimeType()).isEqualTo("image/png");
        assertThat(rules.check("p.JPG", bytes(0xFF, 0xD8, 0xFF, 0xE0)).extension()).isEqualTo("jpg");
        assertThat(rules.check("p.jpeg", bytes(0xFF, 0xD8, 0xFF, 0xE0)).mimeType()).isEqualTo("image/jpeg");
        assertThat(rules.check("a.gif", text("GIF89a....")).mimeType()).isEqualTo("image/gif");
        assertThat(rules.check("a.webp", text("RIFF....WEBPVP8 ")).mimeType()).isEqualTo("image/webp");
        assertThat(rules.check("a.heic", text("....ftypheic....")).extension()).isEqualTo("heic");
        assertThat(rules.check("a.docx", text("PK\u0003\u0004")).extension()).isEqualTo("docx");
        assertThat(rules.check("a.xlsx", text("PK\u0003\u0004")).extension()).isEqualTo("xlsx");
        assertThat(rules.check("a.doc", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1)).extension()).isEqualTo("doc");
        assertThat(rules.check("a.xls", bytes(0xD0, 0xCF, 0x11, 0xE0, 0xA1)).extension()).isEqualTo("xls");
        assertThat(rules.check("a.csv", text("a,b\n1,2")).mimeType()).isEqualTo("text/csv");
        assertThat(rules.check("notes.txt", text("plain words")).mimeType()).isEqualTo("text/plain");
    }

    @Test
    @DisplayName("a renamed file, or text with markup a browser would run, is refused")
    void disguised() {
        assertThatThrownBy(() -> rules.check("fake.pdf", text("not a pdf"))).hasMessageContaining("don't match its .pdf");
        assertThatThrownBy(() -> rules.check("fake.png", text("PNG"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.jpg", bytes(0xFF))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.gif", text("GIF"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.webp", text("RIFF....WAVE"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.heic", text("short"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.docx", text("zip?"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("fake.doc", text("text!"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("evil.txt", text("hello <script>alert(1)</script>"))).hasMessageContaining("don't match");
        assertThatThrownBy(() -> rules.check("evil.csv", text("<HTML><body>"))).hasMessageContaining("don't match");
        assertThat(FileRules.contentMatches("zip", text("PK"))).isFalse();
    }

    @Test
    @DisplayName("file names are made safe: folders dropped, odd characters replaced, long names shortened, blanks named")
    void names() {
        assertThat(FileRules.clean("C:\\Users\\me\\lease.pdf")).isEqualTo("lease.pdf");
        assertThat(FileRules.clean("../../etc/passwd")).isEqualTo("passwd");
        assertThat(FileRules.clean("a<b>c|d?.pdf")).isEqualTo("a_b_c_d_.pdf");
        assertThat(FileRules.clean("   ")).isEqualTo("file");
        String longName = "x".repeat(300) + ".pdf";
        assertThat(FileRules.clean(longName)).hasSize(180).endsWith(".pdf");
        assertThat(FileRules.clean("y".repeat(300))).hasSize(180);
    }

    @Test
    @DisplayName("the hash is stable and previews are only for safe types")
    void hashAndPreview() {
        assertThat(FileRules.sha256(text("abc"))).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(FileRules.previewable("application/pdf")).isTrue();
        assertThat(FileRules.previewable("image/png")).isTrue();
        assertThat(FileRules.previewable("image/heic")).isFalse();
        assertThat(FileRules.previewable("text/plain")).isTrue();
        assertThat(FileRules.previewable("application/zip")).isFalse();
        assertThat(FileRules.previewable(null)).isFalse();
        assertThat(rules.maxBytes()).isEqualTo(1024 * 1024);
    }
}
