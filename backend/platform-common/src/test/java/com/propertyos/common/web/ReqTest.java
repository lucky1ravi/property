package com.propertyos.common.web;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReqTest {
    @Test
    @DisplayName("blank and missing text read as null; real text is trimmed")
    void text() {
        assertThat(Req.str(Map.of("a", "  hi  "), "a")).isEqualTo("hi");
        assertThat(Req.str(Map.of("a", "  "), "a")).isNull();
        assertThat(Req.str(null, "a")).isNull();
    }

    @Test
    @DisplayName("a required field that is missing gives a readable message")
    void required() {
        assertThatThrownBy(() -> Req.required(Map.of(), "firstName"))
                .isInstanceOf(ApiException.class).hasMessage("First name is required");
    }

    @Test
    @DisplayName("money accepts $ and commas and rounds to cents")
    void money() {
        assertThat(Req.money(Map.of("rent", "$1,250.456"), "rent")).isEqualByComparingTo("1250.46");
        assertThat(Req.money(Map.of(), "rent")).isNull();
    }

    @Test
    @DisplayName("money rejects negatives and non-numbers")
    void badMoney() {
        assertThatThrownBy(() -> Req.money(Map.of("rent", "-5"), "rent")).hasMessageContaining("can't be negative");
        assertThatThrownBy(() -> Req.money(Map.of("rent", "abc"), "rent")).hasMessageContaining("must be a number");
    }

    @Test
    @DisplayName("ids and dates are parsed, and bad ones are 400 errors")
    void idsAndDates() {
        UUID id = UUID.randomUUID();
        assertThat(Req.uuid(Map.of("id", id.toString()), "id")).isEqualTo(id);
        assertThat(Req.date(Map.of("d", "2026-10-29T10:00:00Z"), "d")).isEqualTo(LocalDate.of(2026, 10, 29));
        assertThatThrownBy(() -> Req.uuid(Map.of("id", "nope"), "id")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Req.date(Map.of("d", "29/10/2026"), "d")).hasMessageContaining("YYYY-MM-DD");
    }

    @Test
    @DisplayName("email must look like an address and is lower-cased")
    void email() {
        assertThat(Req.email(Map.of("e", "Sarah@Example.COM"), "e")).isEqualTo("sarah@example.com");
        assertThatThrownBy(() -> Req.email(Map.of("e", "not-an-email"), "e")).hasMessage("Enter a valid email address");
    }

    @Test
    @DisplayName("lists and flags have safe defaults")
    void collections() {
        assertThat(Req.strings(Map.of("tags", List.of("a", "b")), "tags")).containsExactly("a", "b");
        assertThat(Req.strings(Map.of(), "tags")).isEmpty();
        assertThat(Req.bool(Map.of("on", "true"), "on")).isTrue();
        assertThat(Req.bool(Map.of(), "on")).isFalse();
    }
}
