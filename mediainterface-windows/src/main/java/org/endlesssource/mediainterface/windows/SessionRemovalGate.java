package org.endlesssource.mediainterface.windows;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Time-based hysteresis for session removal.
 *
 * <p>SMTC transiently omits sessions from {@code GetSessions()} during track
 * and app-state transitions. Because the session list is reconciled
 * immediately on every {@code SessionsChanged} event, removing on first
 * absence would tear down and recreate sessions (and emit spurious
 * onSessionRemoved/onSessionAdded) on every such gap. A tracked session is
 * therefore only removed once it has been absent for a full grace period.</p>
 *
 * <p>Thread-safe; time is passed in explicitly so the logic is testable.</p>
 */
final class SessionRemovalGate {
    private final long graceMillis;
    private final Map<String, Long> missingSinceMillis = new HashMap<>();

    SessionRemovalGate(long graceMillis) {
        if (graceMillis < 0) {
            throw new IllegalArgumentException("graceMillis must be >= 0, was " + graceMillis);
        }
        this.graceMillis = graceMillis;
    }

    /**
     * Records which tracked sessions are absent from a successful enumeration
     * and returns those whose absence has now outlasted the grace period.
     *
     * @param trackedIds sessions we currently hold
     * @param presentIds sessions reported by the (authoritative) enumeration
     * @param nowMillis current time
     * @return session ids whose removal is confirmed
     */
    synchronized Set<String> confirmRemovals(Set<String> trackedIds, Set<String> presentIds, long nowMillis) {
        // Reset the clock for sessions that reappeared or are no longer tracked.
        missingSinceMillis.keySet().removeIf(id -> presentIds.contains(id) || !trackedIds.contains(id));

        Set<String> confirmed = new LinkedHashSet<>();
        for (String id : trackedIds) {
            if (presentIds.contains(id)) {
                continue;
            }
            Long since = missingSinceMillis.putIfAbsent(id, nowMillis);
            if (since != null && nowMillis - since >= graceMillis) {
                missingSinceMillis.remove(id);
                confirmed.add(id);
            }
        }
        return confirmed;
    }

    /**
     * @return true while at least one tracked session is inside its grace
     * period, i.e. a recheck should be scheduled.
     */
    synchronized boolean hasPending() {
        return !missingSinceMillis.isEmpty();
    }

    synchronized void forget(String id) {
        missingSinceMillis.remove(id);
    }

    synchronized void clear() {
        missingSinceMillis.clear();
    }
}
