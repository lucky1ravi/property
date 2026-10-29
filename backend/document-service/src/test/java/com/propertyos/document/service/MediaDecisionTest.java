package com.propertyos.document.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertyos.common.audit.AuditService;
import com.propertyos.common.client.ServiceClient;
import com.propertyos.common.config.ConfigClient;
import com.propertyos.common.events.Notifier;
import com.propertyos.common.rls.Db;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** The photo-review rule: what the AI may publish on its own, and what must wait for a person. */
class MediaDecisionTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private ConfigClient config;
    private MediaService media;

    @BeforeEach
    void setUp() {
        config = Mockito.mock(ConfigClient.class);
        when(config.number(eq("moderation/autoApproveConfidence"), anyString())).thenReturn(new BigDecimal("80"));
        when(config.number(eq("moderation/autoRejectConfidence"), anyString())).thenReturn(new BigDecimal("98"));
        when(config.flag(eq("moderation/autoRejectEnabled"), anyBoolean())).thenReturn(false);
        media = new MediaService(Mockito.mock(Db.class), config, Mockito.mock(FileRules.class), Mockito.mock(ServiceClient.class),
                Mockito.mock(AuditService.class), Mockito.mock(Notifier.class), mapper);
    }

    private MediaService.Decision decide(String kind, String verdict, double confidence, String category) {
        return media.decide(kind, mapper.createObjectNode().put("verdict", verdict).put("confidence", confidence).put("category", category));
    }

    @Test
    @DisplayName("a confident, safe property photo is approved automatically")
    void autoApprove() {
        MediaService.Decision d = decide("PROPERTY", "SAFE", 0.95, "PROPERTY");
        assertThat(d.status()).isEqualTo("APPROVED");
        assertThat(d.decidedBy()).isEqualTo("AI");
    }

    @Test
    @DisplayName("confidence exactly at the threshold is approved; just below goes to review")
    void thresholdEdge() {
        assertThat(decide("PROPERTY", "SAFE", 0.80, "PROPERTY").status()).isEqualTo("APPROVED");
        MediaService.Decision below = decide("PROPERTY", "SAFE", 0.79, "PROPERTY");
        assertThat(below.status()).isEqualTo("PENDING_REVIEW");
        assertThat(below.flagReason()).contains("below the 80%");
    }

    @Test
    @DisplayName("the threshold is a company setting")
    void configurableThreshold() {
        when(config.number(eq("moderation/autoApproveConfidence"), anyString())).thenReturn(new BigDecimal("95"));
        assertThat(decide("PROPERTY", "SAFE", 0.90, "PROPERTY").status()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("a safe photo of the wrong kind of thing waits for a person")
    void wrongCategory() {
        MediaService.Decision d = decide("PROPERTY", "SAFE", 0.99, "PERSON_PHOTO");
        assertThat(d.status()).isEqualTo("PENDING_REVIEW");
        assertThat(d.flagReason()).contains("property photo");
    }

    @Test
    @DisplayName("a profile photo must look like a person")
    void profilePhoto() {
        assertThat(decide("PROFILE", "SAFE", 0.9, "PERSON_PHOTO").status()).isEqualTo("APPROVED");
        assertThat(decide("PROFILE", "SAFE", 0.9, "PROPERTY").status()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("unsafe photos are never published; they go to a person")
    void unsafe() {
        MediaService.Decision d = decide("PROPERTY", "UNSAFE", 0.99, "EXPLICIT");
        assertThat(d.status()).isEqualTo("PENDING_REVIEW");
        assertThat(d.decidedBy()).isNull();
    }

    @Test
    @DisplayName("auto-reject only happens when switched on and the model is very sure")
    void autoReject() {
        when(config.flag(eq("moderation/autoRejectEnabled"), anyBoolean())).thenReturn(true);
        assertThat(decide("PROPERTY", "UNSAFE", 0.99, "EXPLICIT").status()).isEqualTo("REJECTED");
        assertThat(decide("PROPERTY", "UNSAFE", 0.90, "EXPLICIT").status()).isEqualTo("PENDING_REVIEW");
    }

    @Test
    @DisplayName("when the check could not run the photo fails safe to review")
    void unknown() {
        MediaService.Decision d = decide("PROPERTY", "UNKNOWN", 0, "OTHER");
        assertThat(d.status()).isEqualTo("PENDING_REVIEW");
        assertThat(d.flagReason()).contains("could not decide");
    }
}
