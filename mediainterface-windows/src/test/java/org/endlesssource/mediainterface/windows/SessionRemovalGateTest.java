package org.endlesssource.mediainterface.windows;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRemovalGateTest {
    private static final long GRACE = 3000L;

    @Test
    void keepsSessionOnFirstAbsence() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        assertTrue(confirmed.isEmpty());
        assertTrue(gate.hasPending());
    }

    @Test
    void keepsSessionWhileWithinGrace() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L + GRACE - 1);
        assertTrue(confirmed.isEmpty());
        assertTrue(gate.hasPending());
    }

    @Test
    void removesSessionAfterGraceElapsed() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L + GRACE);
        assertEquals(Set.of("a"), confirmed);
        assertFalse(gate.hasPending());
    }

    @Test
    void reappearingSessionResetsClock() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        // Session flaps back in.
        gate.confirmRemovals(Set.of("a"), Set.of("a"), 2000L);
        assertFalse(gate.hasPending());
        // Goes missing again: clock restarts, no removal at old deadline.
        gate.confirmRemovals(Set.of("a"), Set.of(), 3000L);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L + GRACE);
        assertTrue(confirmed.isEmpty());
        confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 3000L + GRACE);
        assertEquals(Set.of("a"), confirmed);
    }

    @Test
    void tracksMissStreaksPerSession() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a", "b"), Set.of("b"), 1000L);
        gate.confirmRemovals(Set.of("a", "b"), Set.of(), 2000L);
        // "a" missing since 1000, "b" missing since 2000.
        Set<String> confirmed = gate.confirmRemovals(Set.of("a", "b"), Set.of(), 1000L + GRACE);
        assertEquals(Set.of("a"), confirmed);
        confirmed = gate.confirmRemovals(Set.of("a", "b"), Set.of(), 2000L + GRACE);
        assertEquals(Set.of("b"), confirmed);
    }

    @Test
    void newSessionsAreNotGated() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of("a", "new"), 1000L);
        assertTrue(confirmed.isEmpty());
        assertFalse(gate.hasPending());
    }

    @Test
    void untrackedIdsAreDropped() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        // "a" is no longer tracked (removed externally): streak must not linger.
        Set<String> confirmed = gate.confirmRemovals(Set.of(), Set.of(), 1000L + GRACE);
        assertTrue(confirmed.isEmpty());
        assertFalse(gate.hasPending());
    }

    @Test
    void forgetClearsStreak() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        gate.forget("a");
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L + GRACE);
        assertTrue(confirmed.isEmpty());
    }

    @Test
    void clearResetsEverything() {
        SessionRemovalGate gate = new SessionRemovalGate(GRACE);
        gate.confirmRemovals(Set.of("a", "b"), Set.of(), 1000L);
        gate.clear();
        assertFalse(gate.hasPending());
    }

    @Test
    void zeroGraceStillRequiresSecondEnumeration() {
        SessionRemovalGate gate = new SessionRemovalGate(0L);
        Set<String> confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        assertTrue(confirmed.isEmpty());
        confirmed = gate.confirmRemovals(Set.of("a"), Set.of(), 1000L);
        assertEquals(Set.of("a"), confirmed);
    }

    @Test
    void rejectsNegativeGrace() {
        assertThrows(IllegalArgumentException.class, () -> new SessionRemovalGate(-1L));
    }
}
