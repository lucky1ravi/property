package com.propertyos.leasing.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignSecurityTest {
    private static final byte[] BODY = "{\"type\":\"lead\",\"name\":\"Rae\",\"email\":\"rae@example.com\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    @DisplayName("HMAC-SHA256 matches the published test vector (RFC 4231, case 2)")
    void knownVector() {
        // key "Jefe", data "what do ya want for nothing?"
        assertThat(CampaignSecurity.sign("Jefe", "what do ya want for nothing?".getBytes(StandardCharsets.UTF_8)))
                .isEqualTo("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843");
    }

    @Test
    @DisplayName("the right signature is accepted, with or without the sha256= prefix and in either case")
    void accepts() {
        String sig = CampaignSecurity.sign("whsec_abc", BODY);
        assertThat(CampaignSecurity.verify("whsec_abc", sig, BODY)).isTrue();
        assertThat(CampaignSecurity.verify("whsec_abc", "sha256=" + sig, BODY)).isTrue();
        assertThat(CampaignSecurity.verify("whsec_abc", "SHA256=" + sig.toUpperCase(), BODY)).isTrue();
        assertThat(CampaignSecurity.verify("whsec_abc", "  " + sig + " ", BODY)).isTrue();
    }

    @Test
    @DisplayName("a missing, empty, malformed, wrong-secret or tampered signature is rejected")
    void rejects() {
        String sig = CampaignSecurity.sign("whsec_abc", BODY);
        assertThat(CampaignSecurity.verify("whsec_abc", null, BODY)).isFalse();
        assertThat(CampaignSecurity.verify("whsec_abc", "", BODY)).isFalse();
        assertThat(CampaignSecurity.verify("whsec_abc", "sha256=", BODY)).isFalse();
        assertThat(CampaignSecurity.verify("whsec_abc", "not-hex-and-the-wrong-length", BODY)).isFalse();
        assertThat(CampaignSecurity.verify("whsec_abc", sig.substring(0, 63) + (sig.charAt(63) == '0' ? '1' : '0'), BODY)).isFalse();   // one character off
        assertThat(CampaignSecurity.verify("another-secret", sig, BODY)).isFalse();
        assertThat(CampaignSecurity.verify("whsec_abc", sig, "{\"type\":\"lead\",\"name\":\"Eve\"}".getBytes(StandardCharsets.UTF_8))).isFalse();
        assertThat(CampaignSecurity.verify(null, sig, BODY)).isFalse();
    }

    @Test
    @DisplayName("a rotated secret signs differently, so the old secret's signatures stop working")
    void rotation() {
        String oldSig = CampaignSecurity.sign("old-secret", BODY);
        assertThat(CampaignSecurity.verify("new-secret", oldSig, BODY)).isFalse();
    }

    @Test
    @DisplayName("generated keys have the expected shape, fit their columns and do not repeat")
    void keys() {
        Set<String> secrets = new HashSet<>(), publics = new HashSet<>(), codes = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String s = CampaignSecurity.newSecret(), p = CampaignSecurity.newPublicKey(), c = CampaignSecurity.newTrackingCode();
            assertThat(s).startsWith("whsec_").matches("[A-Za-z0-9_-]+").hasSizeLessThanOrEqualTo(80);
            assertThat(p).matches("cmp_[0-9a-f]{24}").hasSizeLessThanOrEqualTo(40);
            assertThat(c).matches("[a-z2-9]{8}").doesNotContain("l", "o", "i", "0", "1");
            secrets.add(s); publics.add(p); codes.add(c);
        }
        assertThat(secrets).hasSize(200);
        assertThat(publics).hasSize(200);
        assertThat(codes.size()).isGreaterThan(195);
    }

    @Test
    @DisplayName("the per-campaign rate window allows the limit, rejects beyond it, and starts afresh the next minute")
    void rateWindow() {
        CampaignInbound in = new CampaignInbound(null, null, null, null, null, null, null);
        for (int i = 1; i <= 3; i++) assertThat(in.overLimit("cmp_a", 3, 100)).isFalse();
        assertThat(in.overLimit("cmp_a", 3, 100)).isTrue();
        assertThat(in.overLimit("cmp_b", 3, 100)).isFalse();   // another campaign has its own count
        assertThat(in.overLimit("cmp_a", 3, 101)).isFalse();   // next minute
    }
}
