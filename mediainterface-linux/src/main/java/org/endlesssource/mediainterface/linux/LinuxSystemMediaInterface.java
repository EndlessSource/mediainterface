package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.exceptions.DBusException;
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

/**
 * Linux implementation using D-Bus MPRIS
 */
public class LinuxSystemMediaInterface implements SystemMediaInterface {
    private static final Logger logger = LoggerFactory.getLogger(LinuxSystemMediaInterface.class);

    private final DBusConnection connection;
    private final Map<String, LinuxMediaSession> sessions = new ConcurrentHashMap<>();
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final SystemMediaOptions options;

    public LinuxSystemMediaInterface() throws DBusException {
        this(SystemMediaOptions.defaults());
    }

    public LinuxSystemMediaInterface(SystemMediaOptions options) throws DBusException {
        this.options = options;
        this.connection = DBusConnectionBuilder.forSessionBus().build();
        this.executor = options.isEventDrivenEnabled()
                ? Executors.newScheduledThreadPool(2)
                : null;
        discoverSessions();
        if (options.isEventDrivenEnabled()) {
            startSessionMonitoring();
        }
    }

    @Override
    public Optional<MediaSession> getActiveSession() {
        // In Linux, we'll consider the first playing session as "active"
        List<LinuxMediaSession> snapshot = new ArrayList<>(sessions.values());
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
        return sessions.values().stream()
                .filter(session -> session.getApplicationName().toLowerCase()
                        .contains(appName.toLowerCase()))
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

    private void discoverSessions() {
        try {
            DBus dbus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            String[] names = dbus.ListNames();
            for (String name : names) {
                if (name.startsWith("org.mpris.MediaPlayer2.")) {
                    addSession(name);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to discover MPRIS sessions", e);
        }
    }

    private void addSession(String busName) {
        try {
            LinuxMediaSession session = new LinuxMediaSession(
                    connection,
                    busName,
                    options.isEventDrivenEnabled(),
                    options.getSessionUpdateInterval()
            );
            sessions.put(busName, session);

            // Notify listeners
            listeners.forEach(listener -> listener.onSessionAdded(session));
        } catch (Exception e) {
            logger.warn("Failed to add session for {}: {}", busName, e.getMessage());
        }
    }

    private void removeSession(String busName) {
        LinuxMediaSession removed = sessions.remove(busName);
        if (removed != null) {
            removed.close();
            listeners.forEach(listener -> listener.onSessionRemoved(busName));
        }
    }

    private void startSessionMonitoring() {
        // Monitor for new/removed sessions every 1 second
        long intervalMs = options.getSessionPollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::updateSessions, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    private void updateSessions() {
        try {
            DBus dbus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
            String[] currentNames = dbus.ListNames();
            Set<String> mprisNames = new HashSet<>();

            // Find current MPRIS services
            for (String name : currentNames) {
                if (name.startsWith("org.mpris.MediaPlayer2.")) {
                    mprisNames.add(name);
                }
            }

            // Remove sessions that no longer exist
            Set<String> toRemove = new HashSet<>(sessions.keySet());
            toRemove.removeAll(mprisNames);
            toRemove.forEach(this::removeSession);

            // Add new sessions
            for (String name : mprisNames) {
                if (!sessions.containsKey(name)) {
                    addSession(name);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to update sessions", e);
        }
    }

    public void close() {
        if (executor != null) {
            executor.shutdown();
        }
        sessions.values().forEach(LinuxMediaSession::close);
        sessions.clear();
        try {
            connection.close();
        } catch (Exception e) {
            logger.error("Failed to close D-Bus connection", e);
        }
    }
}
