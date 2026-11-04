package com.propertyos.leasing.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The pitch seeder must write nothing unless demo mode and the pitch switch are both on. These seeders are built with no database at all: if the guard failed,
 * the first thing the seeder did would be to use it and the test would blow up.
 */
class PitchGuardTest {
    @Test
    @DisplayName("production (demo mode off) never writes pitch data, even if the switch is on by mistake")
    void productionWritesNothing() {
        assertThatCode(() -> new LeasingSeeder(null, null, false, true, null).seedPitchIfEnabled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("demo mode without the switch writes nothing: the pitch is opt-in")
    void optIn() {
        assertThatCode(() -> new LeasingSeeder(null, null, true, false, null).seedPitchIfEnabled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("both off writes nothing")
    void bothOff() {
        assertThatCode(() -> new LeasingSeeder(null, null, false, false, null).seedPitchIfEnabled()).doesNotThrowAnyException();
    }
}
