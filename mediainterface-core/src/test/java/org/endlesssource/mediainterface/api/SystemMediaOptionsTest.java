package org.endlesssource.mediainterface.api;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemMediaOptionsTest {

    @Test
    void defaults_areExpected() {
        SystemMediaOptions defaults = SystemMediaOptions.defaults();
        assertTrue(defaults.isEventDrivenEnabled());
        assertEquals(SystemMediaOptions.DEFAULT_SESSION_POLL_INTERVAL, defaults.getSessionPollInterval());
        assertEquals(SystemMediaOptions.DEFAULT_SESSION_UPDATE_INTERVAL, defaults.getSessionUpdateInterval());
    }

    @Test
    void withIntervals_rejectsZeroOrNegative() {
        SystemMediaOptions defaults = SystemMediaOptions.defaults();
        assertThrows(IllegalArgumentException.class, () -> defaults.withSessionPollInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSessionPollInterval(Duration.ofMillis(-1)));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSessionUpdateInterval(Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> defaults.withSessionUpdateInterval(Duration.ofMillis(-1)));
    }
}

