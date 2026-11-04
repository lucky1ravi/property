package com.propertyos.leasing.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ListingPhotoTest {
    private static boolean has(String json) throws Exception {
        return ListingService.hasPhoto(new ObjectMapper().readTree(json));
    }

    @Test
    @DisplayName("a list with a real picture address has a photo")
    void real() throws Exception {
        assertThat(has("[\"https://example.com/a.jpg\"]")).isTrue();
        assertThat(has("[null, \"https://example.com/a.jpg\"]")).isTrue();
    }

    @Test
    @DisplayName("an empty list, or one holding only empty slots, has no photo: this is what made blank homes pass the check")
    void empty() throws Exception {
        assertThat(has("[]")).isFalse();
        assertThat(has("[null]")).isFalse();
        assertThat(has("[\"\"]")).isFalse();
        assertThat(has("[\"  \", null]")).isFalse();
        assertThat(has("[{\"url\":\"x\"}]")).isFalse();   // only plain picture addresses count
        assertThat(has("null")).isFalse();
        assertThat(ListingService.hasPhoto(null)).isFalse();
    }
}
