package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Windows implementation over the WinRT Global System Media Transport
 * Controls (SMTC) API.
 *
 * <p>In event-driven mode the native bridge pushes full state through
 * {@link WinRtBridge.NativeEventSink}: the session-id list on every
 * {@code SessionsChanged}, and a complete session payload on every
 * media/playback/timeline change. Java holds that pushed state and never
 * reads WinRT back; user listener callbacks are dispatched on a
 * single-threaded executor so native threads are never blocked by user
 * code.</p>
 *
 * <p>In polling mode the session list and each session's state are pulled
 * from the bridge at the configured intervals instead.</p>
 */
public final class WindowsSystemMediaInterface implements SystemMediaInterface, WinRtBridge.NativeEventSink {
    private static final Logger logger = LoggerFactory.getLogger(WindowsSystemMediaInterface.class);
    /** How long a session must be absent from enumeration before removal. */
    private static final long REMOVAL_GRACE_MS = 3000L;
    /** Retry delay when the WinRT session manager is not yet available. */
    private static final long EVENT_STREAM_RETRY_MS = 3000L;

    private final SystemMediaOptions options;
    private final Map<String, WindowsMediaSession> sessions = new ConcurrentHashMap<>();
    private final SessionRemovalGate removalGate = new SessionRemovalGate(REMOVAL_GRACE_MS);
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final AtomicBoolean removalRecheckPending = new AtomicBoolean();
    /** Last session-id list pushed from native (event-driven mode only). */
    private volatile String[] lastPushedIds = new String[0];
    private volatile boolean closed;

    public WindowsSystemMediaInterface(SystemMediaOptions options) {
        this.options = options;
        WinRtBridge.load();
        WinRtBridge.nativeInit(options.isEventDrivenEnabled());
        logger.debug("Initializing Windows media interface (eventDriven={})", options.isEventDrivenEnabled());
        if (options.isEventDrivenEnabled()) {
            // Single thread keeps pushed updates and listener callbacks ordered.
            this.executor = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
            WinRtBridge.setEventSink(this);
            startEventStream();
        } else {
            this.executor = Executors.newScheduledThreadPool(2, daemonThreadFactory());
            updateSessionsFromNative();
            long pollMs = options.getSessionPollInterval().toMillis();
            executor.scheduleWithFixedDelay(this::updateSessionsFromNative, pollMs, pollMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Subscribes to native pushes. The native side delivers the current
     * session list and each session's state synchronously through the sink
     * before returning, so state is warm once this succeeds. Retries until
     * the WinRT session manager becomes available (e.g. cold boot).
     */
    private void startEventStream() {
        if (closed) {
            return;
        }
        boolean started = false;
        try {
            started = WinRtBridge.nativeStartEventStream();
        } catch (Exception e) {
            logger.debug("Failed to start WinRT event stream: {}", e.getMessage());
        }
        if (started) {
            return;
        }
        logger.debug("WinRT event stream unavailable, retrying in {} ms", EVENT_STREAM_RETRY_MS);
        try {
            executor.schedule(this::startEventStream, EVENT_STREAM_RETRY_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Executor already shut down.
        }
    }

    @Override
    public Optional<MediaSession> getActiveSession() {
        List<WindowsMediaSession> snapshot = new ArrayList<>(sessions.values());
        return snapshot.stream()
                .filter(session -> session.getControls().getPlaybackState() == PlaybackState.PLAYING)
                .findFirst()
                .map(session -> (MediaSession) session)
                .or(() -> snapshot.stream()
                        .findFirst()
                        .map(session -> (MediaSession) session));
    }

    @Override
    public List<MediaSession> getAllSessions() {
        return new ArrayList<>(sessions.values());
    }

    @Override
    public Optional<MediaSession> getSessionByApp(String appName) {
        String query = appName == null ? "" : appName.toLowerCase();
        return sessions.values().stream()
                .filter(session -> session.getApplicationName().toLowerCase().contains(query))
                .findFirst()
                .map(session -> (MediaSession) session);
    }

    @Override
    public boolean hasActiveSessions() {
        return !sessions.isEmpty();
    }

    @Override
    public void addSessionListener(MediaSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeSessionListener(MediaSessionListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean isEventDrivenEnabled() {
        return options.isEventDrivenEnabled();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        WinRtBridge.clearEventSink(this);
        executor.shutdownNow();
        sessions.values().forEach(WindowsMediaSession::close);
        sessions.clear();
        removalGate.clear();
        listeners.clear();
        WinRtBridge.nativeShutdown();
        logger.debug("Windows media interface closed");
    }

    // --- WinRtBridge.NativeEventSink (called from WinRT threads) ------------

    @Override
    public void onSessionListChanged(String[] sessionIds) {
        if (closed || sessionIds == null) {
            return;
        }
        lastPushedIds = sessionIds;
        reconcileSessions(sessionIds);
    }

    @Override
    public void onSessionStateChanged(String sessionId, String[] payload) {
        if (closed || sessionId == null) {
            return;
        }
        WindowsMediaSession session = sessions.get(sessionId);
        if (session == null) {
            // State can arrive before (or without) the corresponding list
            // push; adding here also self-heals an over-eager removal.
            addSession(sessionId);
            session = sessions.get(sessionId);
        }
        if (session != null) {
            session.applyPushedSnapshot(payload);
        }
    }

    // -------------------------------------------------------------------------

    /** Polling-mode refresh: pulls the id list from the bridge. */
    private void updateSessionsFromNative() {
        if (closed) {
            return;
        }
        String[] ids = WinRtBridge.nativeGetSessionIds();
        if (ids == null) {
            // Transient native failure: state unknown, keep all sessions.
            return;
        }
        reconcileSessions(ids);
    }

    private synchronized void reconcileSessions(String[] ids) {
        if (closed) {
            return;
        }
        Set<String> current = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) {
                continue;
            }
            current.add(id);
            if (!sessions.containsKey(id)) {
                addSession(id);
            }
        }

        // SMTC transiently omits sessions during track/app transitions, so
        // absent sessions are only removed after a grace period.
        Set<String> confirmed = removalGate.confirmRemovals(sessions.keySet(), current, System.currentTimeMillis());
        for (String id : confirmed) {
            removeSession(id);
        }
        if (removalGate.hasPending()) {
            scheduleRemovalRecheck();
        }
    }

    /**
     * Re-runs the reconciliation shortly after the grace period so a genuine
     * removal doesn't have to wait for the next event or poll. In event-driven
     * mode the recheck runs against the last pushed list (no native read); a
     * session wrongly removed after a missed re-add event is restored by its
     * next pushed state payload.
     */
    private void scheduleRemovalRecheck() {
        if (closed || !removalRecheckPending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.schedule(() -> {
                removalRecheckPending.set(false);
                if (options.isEventDrivenEnabled()) {
                    reconcileSessions(lastPushedIds);
                } else {
                    updateSessionsFromNative();
                }
            }, REMOVAL_GRACE_MS + 250L, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            removalRecheckPending.set(false);
        }
    }

    private void addSession(String id) {
        if (id == null || id.isBlank()) {
            return;
        }
        WindowsMediaSession[] created = new WindowsMediaSession[1];
        sessions.computeIfAbsent(id, key -> {
            created[0] = new WindowsMediaSession(key, options, executor);
            return created[0];
        });
        if (created[0] == null) {
            return;
        }
        WindowsMediaSession session = created[0];
        logger.debug("Added Windows media session {}", id);
        notifyListeners(listener -> listener.onSessionAdded(session));
    }

    private void removeSession(String id) {
        removalGate.forget(id);
        WindowsMediaSession removed = sessions.remove(id);
        if (removed != null) {
            removed.close();
            logger.debug("Removed Windows media session {}", id);
            notifyListeners(listener -> listener.onSessionRemoved(id));
        }
    }

    /**
     * Dispatches a listener callback on the executor: pushes arrive on WinRT
     * threads, which must never run (or block on) user code.
     */
    private void notifyListeners(Consumer<MediaSessionListener> action) {
        if (!options.isEventDrivenEnabled() || closed) {
            return;
        }
        try {
            executor.execute(() -> {
                if (closed) {
                    return;
                }
                for (MediaSessionListener listener : listeners) {
                    try {
                        action.accept(listener);
                    } catch (Exception e) {
                        logger.debug("Session listener failed: {}", e.getMessage());
                    }
                }
            });
        } catch (Exception ignored) {
            // Executor already shut down.
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "mediainterface-windows-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
