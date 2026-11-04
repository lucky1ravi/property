package com.propertyos.identity.seed;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/** The identity side of the same rule: no company, user or invoice is created unless demo mode and the pitch switch are both on. Built with no database, so touching it would fail. */
class PitchGuardTest {
    @Test
    @DisplayName("production (demo mode off) never writes pitch data, even if the switch is on by mistake")
    void productionWritesNothing() {
        assertThatCode(() -> new IdentitySeeder(null, null, null, false, true, null, null).seedPitchIfEnabled()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("demo mode without the switch writes nothing: the pitch is opt-in")
    void optIn() {
        assertThatCode(() -> new IdentitySeeder(null, null, null, true, false, null, null).seedPitchIfEnabled()).doesNotThrowAnyException();
    }
}
