package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.endlesssource.mediainterface.api.TransportCapabilities;
import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A single MPRIS player session.
 *
 * <p>State is kept fresh primarily by D-Bus signals (PropertiesChanged/Seeked,
 * routed here by {@link LinuxSystemMediaInterface}); a slow reconcile poll
 * heals missed signals. All property reads are batched into a single
 * {@code GetAll} round trip. The playback position is projected from a
 * monotonic anchor at read time, so it stays accurate without polling.</p>
 */
class LinuxMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(LinuxMediaSession.class);
    private static final long POSITION_CORRECTION_TOLERANCE_MS = 1500L;
    private static final double RATE_EPSILON = 0.0001d;
    private static final long MIN_RECONCILE_INTERVAL_MS = 2000L;
    private static final String PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player";

    private final String busName;
    private final MprisMediaPlayer2 mediaPlayer2;
    private final MprisPlayer player;
    private final Properties properties;
    private final LinuxMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> scheduledTasks = new ArrayList<>();
    private final AtomicBoolean refreshPending = new AtomicBoolean();
    private final boolean eventDrivenEnabled;
    private final boolean positionUpdatesEnabled;
    private final String applicationName;
    private volatile boolean closed;

    // Latest observed state; replaced under stateLock, read lock-free.
    private final Object stateLock = new Object();
    private volatile LinuxNowPlaying cachedMetadata;
    private volatile PlaybackState cachedState = PlaybackState.UNKNOWN;
    private volatile boolean cachedActive;
    private volatile PositionAnchor anchor = PositionAnchor.EMPTY;
    private volatile Optional<ObjectPath> cachedTrackId = Optional.empty();

    LinuxMediaSession(DBusConnection connection,
                      String busName,
                      SystemMediaOptions options,
                      ScheduledExecutorService executor) throws DBusException {
        this.busName = busName;
        this.mediaPlayer2 = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", MprisMediaPlayer2.class);
        this.player = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", MprisPlayer.class);
        this.properties = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", Properties.class);
        this.controls = new LinuxMediaTransportControls(player, this::getCachedTrackId);
        this.eventDrivenEnabled = options.isEventDrivenEnabled();
        this.positionUpdatesEnabled = options.isPositionUpdatesEnabled();
        this.executor = executor;
        this.applicationName = resolveApplicationName();

        long updateIntervalMs = options.getSessionUpdateInterval().toMillis();

        // Warm cache immediately so first reads/listener registration see current state.
        refreshNow();

        if (eventDrivenEnabled) {
            // Signals keep the cache fresh; reconcile slowly to heal missed
            // signals and correct position drift.
            long reconcileMs = Math.max(MIN_RECONCILE_INTERVAL_MS, updateIntervalMs * 10);
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    this::refreshNow, reconcileMs, reconcileMs, TimeUnit.MILLISECONDS));
            if (positionUpdatesEnabled) {
                // Pure-CPU tick that emits position progression events while
                // playing; involves no D-Bus traffic.
                scheduledTasks.add(executor.scheduleWithFixedDelay(
                        this::emitPositionTick, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
            }
        } else {
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    this::refreshNow, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        LinuxNowPlaying metadata = cachedMetadata;
        if (metadata == null) {
            return Optional.empty();
        }
        return Optional.of(new ProjectedNowPlaying(metadata));
    }

    @Override
    public MediaTransportControls getControls() {
        return controls;
    }

    @Override
    public String getApplicationName() {
        return applicationName;
    }

    @Override
    public String getSessionId() {
        return busName;
    }

    @Override
    public boolean isActive() {
        return cachedActive;
    }

    @Override
    public void addListener(MediaSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(MediaSessionListener listener) {
        listeners.remove(listener);
    }

    void close() {
        closed = true;
        listeners.clear();
        for (ScheduledFuture<?> task : scheduledTasks) {
            task.cancel(false);
        }
        scheduledTasks.clear();
    }

    /**
     * Called (from the signal dispatch path) when this player emitted a
     * PropertiesChanged signal. Coalesces bursts into a single refresh.
     */
    void requestRefresh() {
        if (closed || !refreshPending.compareAndSet(false, true)) {
            return;
        }
        try {
            executor.execute(() -> {
                refreshPending.set(false);
                refreshNow();
            });
        } catch (Exception e) {
            refreshPending.set(false);
        }
    }

    /**
     * Called when this player emitted a Seeked signal (position in micros).
     */
    void onSeeked(long positionMicros) {
        if (closed) {
            return;
        }
        Duration position = Duration.ofNanos(Math.max(0L, positionMicros) * 1000);
        synchronized (stateLock) {
            PositionAnchor current = anchor;
            anchor = new PositionAnchor(Optional.of(position), System.nanoTime(), current.state, current.rate);
        }
        if (eventDrivenEnabled) {
            Optional<NowPlaying> now = getNowPlaying();
            listeners.forEach(listener -> listener.onNowPlayingChanged(this, now));
        }
    }

    Optional<ObjectPath> getCachedTrackId() {
        return cachedTrackId;
    }

    private void refreshNow() {
        if (closed) {
            return;
        }
        try {
            Map<String, Object> props = fetchPlayerProperties();
            applySnapshot(props);
        } catch (Exception e) {
            logger.debug("Error refreshing session {}: {}", busName, e.getMessage());
        }
    }

    /**
     * Fetch all player properties in one round trip; falls back to individual
     * Get calls for players with broken GetAll implementations.
     */
    private Map<String, Object> fetchPlayerProperties() {
        try {
            Map<String, Variant<?>> all = properties.GetAll(PLAYER_INTERFACE);
            return new HashMap<>(all);
        } catch (Exception e) {
            logger.debug("GetAll failed for {}, falling back to individual gets: {}", busName, e.getMessage());
        }

        Map<String, Object> fallback = new HashMap<>();
        putIfPresent(fallback, "PlaybackStatus");
        putIfPresent(fallback, "Metadata");
        putIfPresent(fallback, "Position");
        putIfPresent(fallback, "Rate");
        return fallback;
    }

    private void putIfPresent(Map<String, Object> target, String property) {
        try {
            Object value = properties.Get(PLAYER_INTERFACE, property);
            if (value != null) {
                target.put(property, value);
            }
        } catch (Exception ignored) {
            // Property not offered by this player.
        }
    }

    private void applySnapshot(Map<String, Object> props) {
        PlaybackState newState = parsePlaybackStatus(unwrapString(props.get("PlaybackStatus")));
        double rate = unwrapDouble(props.get("Rate")).orElse(1.0d);
        Optional<Duration> rawPosition = unwrapLong(props.get("Position"))
                .map(micros -> Duration.ofNanos(micros * 1000));
        Optional<Map<String, Object>> metadataMap = MprisMetadataUtils.toMetadataMap(props.get("Metadata"));
        LinuxNowPlaying newMetadata = metadataMap.map(LinuxNowPlaying::new).orElse(null);

        controls.updatePlaybackState(newState);
        controls.updateCapabilities(parseCapabilities(props));

        LinuxNowPlaying oldMetadata;
        PlaybackState oldState;
        boolean oldActive;
        boolean newActive;
        synchronized (stateLock) {
            oldMetadata = cachedMetadata;
            oldState = cachedState;
            oldActive = cachedActive;

            updateAnchor(newMetadata, rawPosition, newState, rate);

            cachedMetadata = newMetadata;
            cachedState = newState;
            cachedTrackId = newMetadata == null ? Optional.empty() : newMetadata.getTrackId();
            newActive = computeActive(newState, newMetadata);
            cachedActive = newActive;
        }

        if (!eventDrivenEnabled || closed) {
            return;
        }
        if (newState != oldState) {
            listeners.forEach(listener -> listener.onPlaybackStateChanged(this, newState));
        }
        if (newActive != oldActive) {
            listeners.forEach(listener -> listener.onSessionActiveChanged(this, newActive));
        }
        if (!sameMedia(oldMetadata, newMetadata)) {
            Optional<NowPlaying> now = getNowPlaying();
            listeners.forEach(listener -> listener.onNowPlayingChanged(this, now));
        }
    }

    // Must be called under stateLock.
    private void updateAnchor(LinuxNowPlaying newMetadata,
                              Optional<Duration> rawPosition,
                              PlaybackState state,
                              double rate) {
        long nowNanos = System.nanoTime();
        PositionAnchor current = anchor;
        String newTrackKey = trackKey(newMetadata);
        String oldTrackKey = trackKey(cachedMetadata);
        boolean trackChanged = !Objects.equals(newTrackKey, oldTrackKey);

        if (trackChanged) {
            anchor = new PositionAnchor(rawPosition, nowNanos, state, rate);
            return;
        }
        if (rawPosition.isPresent()) {
            Optional<Duration> predicted = current.project(nowNanos);
            if (shouldAnchorToRaw(current, rawPosition.get(), predicted, state, rate)) {
                anchor = new PositionAnchor(rawPosition, nowNanos, state, rate);
            } else {
                anchor = new PositionAnchor(current.position, current.monotonicNanos, state, rate);
            }
            return;
        }
        if (state != current.state || Math.abs(rate - current.rate) > RATE_EPSILON) {
            anchor = new PositionAnchor(current.project(nowNanos), nowNanos, state, rate);
        }
    }

    private static boolean shouldAnchorToRaw(PositionAnchor current,
                                             Duration rawPosition,
                                             Optional<Duration> predicted,
                                             PlaybackState state,
                                             double rate) {
        if (current.position.isEmpty() || predicted.isEmpty()) {
            return true;
        }
        if (state != current.state || Math.abs(rate - current.rate) > RATE_EPSILON) {
            return true;
        }
        long diffMillis = Math.abs(rawPosition.minus(predicted.get()).toMillis());
        if (diffMillis > POSITION_CORRECTION_TOLERANCE_MS) {
            return true;
        }
        // Avoid backward jitter from coarse/stale Position values while playing.
        boolean backward = state == PlaybackState.PLAYING && rawPosition.compareTo(predicted.get()) < 0;
        return !backward;
    }

    private void emitPositionTick() {
        if (closed || listeners.isEmpty() || cachedState != PlaybackState.PLAYING || cachedMetadata == null) {
            return;
        }
        Optional<NowPlaying> now = getNowPlaying();
        listeners.forEach(listener -> listener.onNowPlayingChanged(this, now));
    }

    private Optional<Duration> currentPosition() {
        if (!positionUpdatesEnabled) {
            return Optional.empty();
        }
        Optional<Duration> projected = anchor.project(System.nanoTime());
        if (projected.isEmpty()) {
            return Optional.empty();
        }
        LinuxNowPlaying metadata = cachedMetadata;
        Optional<Duration> duration = metadata == null ? Optional.empty() : metadata.getDuration();
        if (duration.isPresent() && !duration.get().isZero() && projected.get().compareTo(duration.get()) > 0) {
            return duration;
        }
        return projected;
    }

    private static TransportCapabilities parseCapabilities(Map<String, Object> props) {
        return new TransportCapabilities(
                unwrapBoolean(props.get("CanPlay")).orElse(true),
                unwrapBoolean(props.get("CanPause")).orElse(true),
                unwrapBoolean(props.get("CanGoNext")).orElse(true),
                unwrapBoolean(props.get("CanGoPrevious")).orElse(true),
                unwrapBoolean(props.get("CanControl")).orElse(true),
                unwrapBoolean(props.get("CanSeek")).orElse(false));
    }

    private static PlaybackState parsePlaybackStatus(String status) {
        if (status == null) {
            return PlaybackState.UNKNOWN;
        }
        switch (status.toLowerCase()) {
            case "playing":
                return PlaybackState.PLAYING;
            case "paused":
                return PlaybackState.PAUSED;
            case "stopped":
                return PlaybackState.STOPPED;
            default:
                return PlaybackState.UNKNOWN;
        }
    }

    private static boolean computeActive(PlaybackState state, LinuxNowPlaying nowPlaying) {
        if (state == PlaybackState.PLAYING) {
            return true;
        }
        if (state == PlaybackState.UNKNOWN && nowPlaying != null) {
            return nowPlaying.getTitle().map(title -> !title.isEmpty()).orElse(false);
        }
        return false;
    }

    private String resolveApplicationName() {
        try {
            String identity = mediaPlayer2.getIdentity();
            return identity != null ? identity : extractAppNameFromBusName();
        } catch (Exception e) {
            return extractAppNameFromBusName();
        }
    }

    private String extractAppNameFromBusName() {
        // org.mpris.MediaPlayer2.spotify -> Spotify
        String[] parts = busName.split("\\.");
        if (parts.length >= 4) {
            String name = parts[3];
            return name.isEmpty() ? busName : name.substring(0, 1).toUpperCase() + name.substring(1);
        }
        return busName;
    }

    private static String trackKey(LinuxNowPlaying nowPlaying) {
        if (nowPlaying == null) {
            return null;
        }
        return nowPlaying.getTitle().orElse("") + "|" +
                nowPlaying.getArtist().orElse("") + "|" +
                nowPlaying.getAlbum().orElse("") + "|" +
                nowPlaying.getDuration().map(Duration::toMillis).orElse(-1L);
    }

    private static boolean sameMedia(LinuxNowPlaying a, LinuxNowPlaying b) {
        if (a == null || b == null) {
            return a == b;
        }
        return a.getTitle().equals(b.getTitle()) &&
                a.getArtist().equals(b.getArtist()) &&
                a.getAlbum().equals(b.getAlbum()) &&
                a.getArtwork().equals(b.getArtwork()) &&
                a.getDuration().equals(b.getDuration());
    }

    private static Optional<Boolean> unwrapBoolean(Object value) {
        Object unwrapped = MprisMetadataUtils.unwrap(value);
        if (unwrapped instanceof Boolean) {
            return Optional.of((Boolean) unwrapped);
        }
        return Optional.empty();
    }

    private static Optional<Double> unwrapDouble(Object value) {
        Object unwrapped = MprisMetadataUtils.unwrap(value);
        if (unwrapped instanceof Number) {
            return Optional.of(((Number) unwrapped).doubleValue());
        }
        return Optional.empty();
    }

    private static Optional<Long> unwrapLong(Object value) {
        Object unwrapped = MprisMetadataUtils.unwrap(value);
        if (unwrapped instanceof Number) {
            return Optional.of(((Number) unwrapped).longValue());
        }
        return Optional.empty();
    }

    private static String unwrapString(Object value) {
        Object unwrapped = MprisMetadataUtils.unwrap(value);
        return unwrapped instanceof String ? (String) unwrapped : null;
    }

    /**
     * Immutable position anchor: a known position at a monotonic timestamp,
     * projected forward while playing.
     */
    private static final class PositionAnchor {
        static final PositionAnchor EMPTY =
                new PositionAnchor(Optional.empty(), System.nanoTime(), PlaybackState.UNKNOWN, 1.0d);

        final Optional<Duration> position;
        final long monotonicNanos;
        final PlaybackState state;
        final double rate;

        PositionAnchor(Optional<Duration> position, long monotonicNanos, PlaybackState state, double rate) {
            this.position = position;
            this.monotonicNanos = monotonicNanos;
            this.state = state;
            this.rate = rate;
        }

        Optional<Duration> project(long nowNanos) {
            if (position.isEmpty()) {
                return Optional.empty();
            }
            if (state != PlaybackState.PLAYING || rate <= 0.0d) {
                return position;
            }
            long elapsedNanos = Math.max(0L, nowNanos - monotonicNanos);
            long deltaNanos = Math.max(0L, Math.round(elapsedNanos * rate));
            return Optional.of(position.get().plusNanos(deltaNanos));
        }
    }

    /**
     * NowPlaying view whose position is projected at read time, so it stays
     * accurate without any polling.
     */
    private final class ProjectedNowPlaying implements NowPlaying {
        private final LinuxNowPlaying delegate;

        private ProjectedNowPlaying(LinuxNowPlaying delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<String> getTitle() { return delegate.getTitle(); }

        @Override
        public Optional<String> getArtist() { return delegate.getArtist(); }

        @Override
        public Optional<String> getAlbum() { return delegate.getAlbum(); }

        @Override
        public Optional<String> getArtwork() { return delegate.getArtwork(); }

        @Override
        public Optional<Duration> getDuration() { return delegate.getDuration(); }

        @Override
        public Optional<Duration> getPosition() { return currentPosition(); }

        @Override
        public Map<String, String> getAdditionalMetadata() { return delegate.getAdditionalMetadata(); }

        @Override
        public boolean isLiveStream() { return delegate.isLiveStream(); }

        @Override
        public Instant getLastUpdated() { return delegate.getLastUpdated(); }
    }
}
