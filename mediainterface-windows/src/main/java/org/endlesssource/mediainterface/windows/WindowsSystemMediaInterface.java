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
import java.util.concurrent.TimeUnit;

public final class WindowsSystemMediaInterface implements SystemMediaInterface {
    private static final Logger logger = LoggerFactory.getLogger(WindowsSystemMediaInterface.class);

    private final SystemMediaOptions options;
    private final Map<String, WindowsMediaSession> sessions = new ConcurrentHashMap<>();
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;

    public WindowsSystemMediaInterface(SystemMediaOptions options) {
        this.options = options;
        WinRtBridge.load();
        WinRtBridge.nativeInit(options.isEventDrivenEnabled());
        this.executor = options.isEventDrivenEnabled() ? Executors.newScheduledThreadPool(2) : null;
        logger.debug("Initializing Windows media interface (eventDriven={})", options.isEventDrivenEnabled());
        discoverSessions();
        if (options.isEventDrivenEnabled()) {
            long intervalMs = options.getSessionPollInterval().toMillis();
            executor.scheduleWithFixedDelay(this::updateSessions, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
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
        if (executor != null) {
            executor.shutdownNow();
        }
        sessions.values().forEach(WindowsMediaSession::close);
        sessions.clear();
        listeners.clear();
        WinRtBridge.nativeShutdown();
        logger.debug("Windows media interface closed");
    }

    private void discoverSessions() {
        String[] ids = WinRtBridge.nativeGetSessionIds();
        if (ids == null) {
            logger.debug("No sessions returned during initial discovery");
            return;
        }
        logger.debug("Discovered {} Windows sessions during initialization", ids.length);
        for (String id : ids) {
            addSession(id);
        }
    }

    private void updateSessions() {
        if (closed) {
            return;
        }
        String[] ids = WinRtBridge.nativeGetSessionIds();
        if (ids == null) {
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

        Set<String> removed = new HashSet<>(sessions.keySet());
        removed.removeAll(current);
        for (String id : removed) {
            removeSession(id);
        }
    }

    private void addSession(String id) {
        if (id == null || id.isBlank() || sessions.containsKey(id)) {
            return;
        }
        WindowsMediaSession session = new WindowsMediaSession(
                id,
                options.isEventDrivenEnabled(),
                options.getSessionUpdateInterval()
        );
        sessions.put(id, session);
        logger.debug("Added Windows media session {}", id);
        listeners.forEach(listener -> listener.onSessionAdded(session));
    }

    private void removeSession(String id) {
        WindowsMediaSession removed = sessions.remove(id);
        if (removed != null) {
            removed.close();
            logger.debug("Removed Windows media session {}", id);
            listeners.forEach(listener -> listener.onSessionRemoved(id));
        }
    }
}
