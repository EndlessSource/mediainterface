package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder;
import org.freedesktop.dbus.interfaces.DBus;
import org.freedesktop.dbus.interfaces.DBusSigHandler;
import org.freedesktop.dbus.interfaces.Properties;
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
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Linux implementation using D-Bus MPRIS.
 *
 * <p>Session discovery and state updates are driven by D-Bus signals
 * (NameOwnerChanged for players appearing/disappearing, PropertiesChanged and
 * Seeked for playback state). A slow reconcile poll acts as a safety net for
 * missed signals; if signal registration fails entirely, the implementation
 * falls back to polling at the configured intervals.</p>
 */
public class LinuxSystemMediaInterface implements SystemMediaInterface {
    private static final Logger logger = LoggerFactory.getLogger(LinuxSystemMediaInterface.class);
    private static final String MPRIS_BUS_PREFIX = "org.mpris.MediaPlayer2.";
    private static final String MPRIS_OBJECT_PATH = "/org/mpris/MediaPlayer2";
    private static final long SIGNAL_DRIVEN_RECONCILE_MS = 30_000L;

    private final DBusConnection connection;
    private final DBus dbus;
    private final Map<String, LinuxMediaSession> sessions = new ConcurrentHashMap<>();
    // D-Bus signals carry the sender's unique name (":1.42"), not the
    // well-known MPRIS name, so keep a routing table.
    private final Map<String, String> ownerToBusName = new ConcurrentHashMap<>();
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> signalHandles = new ArrayList<>();
    private final ScheduledExecutorService executor;
    private final SystemMediaOptions options;
    private volatile boolean closed;

    public LinuxSystemMediaInterface() throws DBusException {
        this(SystemMediaOptions.defaults());
    }

    public LinuxSystemMediaInterface(SystemMediaOptions options) throws DBusException {
        this.options = options;
        this.connection = DBusConnectionBuilder.forSessionBus().build();
        this.dbus = connection.getRemoteObject("org.freedesktop.DBus", "/org/freedesktop/DBus", DBus.class);
        this.executor = Executors.newScheduledThreadPool(2, daemonThreadFactory());

        boolean signalsRegistered = registerSignalHandlers();
        discoverSessions();

        // Reconcile the session list periodically: slowly when signals are
        // flowing (safety net only), at the configured poll interval otherwise.
        long reconcileMs = signalsRegistered
                ? SIGNAL_DRIVEN_RECONCILE_MS
                : options.getSessionPollInterval().toMillis();
        executor.scheduleWithFixedDelay(this::reconcileSessions, reconcileMs, reconcileMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<MediaSession> getActiveSession() {
        // Consider the first playing session as "active"
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

    private boolean registerSignalHandlers() {
        try {
            DBusSigHandler<DBus.NameOwnerChanged> nameHandler = signal -> {
                if (closed || signal.name == null || !signal.name.startsWith(MPRIS_BUS_PREFIX)) {
                    return;
                }
                // Do the D-Bus work off the signal dispatch thread.
                String name = signal.name;
                String oldOwner = signal.oldOwner;
                String newOwner = signal.newOwner;
                executor.execute(() -> handleNameOwnerChanged(name, oldOwner, newOwner));
            };
            signalHandles.add(connection.addSigHandler(DBus.NameOwnerChanged.class, nameHandler));

            DBusSigHandler<Properties.PropertiesChanged> propertiesHandler = signal -> {
                if (closed || !MPRIS_OBJECT_PATH.equals(signal.getPath())) {
                    return;
                }
                LinuxMediaSession session = sessionForOwner(signal.getSource());
                if (session != null) {
                    session.requestRefresh();
                }
            };
            signalHandles.add(connection.addSigHandler(Properties.PropertiesChanged.class, propertiesHandler));

            DBusSigHandler<MprisPlayer.Seeked> seekedHandler = signal -> {
                if (closed) {
                    return;
                }
                LinuxMediaSession session = sessionForOwner(signal.getSource());
                if (session != null) {
                    session.onSeeked(signal.getPosition());
                }
            };
            signalHandles.add(connection.addSigHandler(MprisPlayer.Seeked.class, seekedHandler));

            logger.debug("Registered D-Bus signal handlers for MPRIS monitoring");
            return true;
        } catch (Exception e) {
            logger.warn("Failed to register D-Bus signal handlers, falling back to polling: {}", e.getMessage());
            closeSignalHandles();
            return false;
        }
    }

    private LinuxMediaSession sessionForOwner(String uniqueName) {
        if (uniqueName == null) {
            return null;
        }
        String busName = ownerToBusName.get(uniqueName);
        return busName == null ? null : sessions.get(busName);
    }

    private void handleNameOwnerChanged(String name, String oldOwner, String newOwner) {
        if (closed) {
            return;
        }
        if (oldOwner != null && !oldOwner.isEmpty()) {
            ownerToBusName.remove(oldOwner);
        }
        if (newOwner == null || newOwner.isEmpty()) {
            removeSession(name);
        } else {
            ownerToBusName.put(newOwner, name);
            addSession(name);
        }
    }

    private void discoverSessions() {
        try {
            String[] names = dbus.ListNames();
            for (String name : names) {
                if (name.startsWith(MPRIS_BUS_PREFIX)) {
                    addSession(name);
                }
            }
        } catch (Exception e) {
            logger.error("Failed to discover MPRIS sessions", e);
        }
    }

    private void addSession(String busName) {
        if (closed || sessions.containsKey(busName)) {
            return;
        }
        try {
            LinuxMediaSession session = new LinuxMediaSession(connection, busName, options, executor);
            if (sessions.putIfAbsent(busName, session) != null) {
                session.close();
                return;
            }
            mapOwner(busName);
            if (options.isEventDrivenEnabled()) {
                listeners.forEach(listener -> listener.onSessionAdded(session));
            }
        } catch (Exception e) {
            logger.warn("Failed to add session for {}: {}", busName, e.getMessage());
        }
    }

    private void mapOwner(String busName) {
        try {
            String owner = dbus.GetNameOwner(busName);
            if (owner != null && !owner.isEmpty()) {
                ownerToBusName.put(owner, busName);
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve owner of {}: {}", busName, e.getMessage());
        }
    }

    private void removeSession(String busName) {
        LinuxMediaSession removed = sessions.remove(busName);
        if (removed != null) {
            removed.close();
            ownerToBusName.values().remove(busName);
            if (options.isEventDrivenEnabled()) {
                listeners.forEach(listener -> listener.onSessionRemoved(busName));
            }
        }
    }

    private void reconcileSessions() {
        if (closed) {
            return;
        }
        try {
            String[] currentNames = dbus.ListNames();
            Set<String> mprisNames = new HashSet<>();
            for (String name : currentNames) {
                if (name.startsWith(MPRIS_BUS_PREFIX)) {
                    mprisNames.add(name);
                }
            }

            Set<String> toRemove = new HashSet<>(sessions.keySet());
            toRemove.removeAll(mprisNames);
            toRemove.forEach(this::removeSession);

            for (String name : mprisNames) {
                addSession(name);
            }
        } catch (Exception e) {
            logger.error("Failed to reconcile sessions", e);
        }
    }

    private void closeSignalHandles() {
        for (AutoCloseable handle : signalHandles) {
            try {
                handle.close();
            } catch (Exception e) {
                logger.debug("Failed to remove signal handler: {}", e.getMessage());
            }
        }
        signalHandles.clear();
    }

    private static ThreadFactory daemonThreadFactory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "mediainterface-linux-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        closeSignalHandles();
        executor.shutdownNow();
        sessions.values().forEach(LinuxMediaSession::close);
        sessions.clear();
        ownerToBusName.clear();
        listeners.clear();
        try {
            connection.close();
        } catch (Exception e) {
            logger.error("Failed to close D-Bus connection", e);
        }
    }
}
