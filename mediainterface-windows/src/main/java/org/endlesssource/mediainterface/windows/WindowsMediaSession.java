package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * A single Windows SMTC session.
 *
 * <p>In event-driven mode all state arrives as pushed snapshots (built inside
 * the native WinRT change handlers and routed here through
 * {@link WinRtBridge.NativeEventSink}); this class performs no native reads
 * at all. In polling mode the snapshot is pulled over JNI at the configured
 * interval instead. Either way, reads are served from the in-memory cache and
 * the playback position is projected from a monotonic anchor at read
 * time.</p>
 */
final class WindowsMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(WindowsMediaSession.class);

    private final String sessionId;
    private final boolean eventDrivenEnabled;
    private final boolean positionUpdatesEnabled;
    private final WindowsMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final List<ScheduledFuture<?>> scheduledTasks = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private volatile String cachedAppName;
    private volatile boolean cachedActive;
    private volatile PlaybackState cachedState = PlaybackState.UNKNOWN;
    private volatile MediaSnapshot cachedMedia;
    private volatile PositionAnchor anchor = PositionAnchor.EMPTY;
    private volatile Boolean lastActive;

    WindowsMediaSession(String sessionId, SystemMediaOptions options, ScheduledExecutorService executor) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.eventDrivenEnabled = options.isEventDrivenEnabled();
        this.positionUpdatesEnabled = options.isPositionUpdatesEnabled();
        this.controls = new WindowsMediaTransportControls(sessionId);
        this.executor = executor;
        this.cachedAppName = sessionId;

        long updateIntervalMs = options.getSessionUpdateInterval().toMillis();

        if (eventDrivenEnabled) {
            // State arrives exclusively through pushed snapshots; the initial
            // one is delivered right after creation (start stream or the
            // SessionsChanged handler pushes every session's state).
            if (positionUpdatesEnabled) {
                // Pure-CPU tick emitting position progression while playing;
                // involves no JNI traffic.
                scheduledTasks.add(executor.scheduleWithFixedDelay(
                        this::emitPositionTick, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
            }
        } else {
            // Warm the cache immediately so first reads see current state.
            refreshNow();
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    this::refreshNow, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        MediaSnapshot media = cachedMedia;
        if (media == null) {
            return Optional.empty();
        }
        return Optional.of(media.toNowPlaying(currentPositionMs(media)));
    }

    @Override
    public MediaTransportControls getControls() {
        return controls;
    }

    @Override
    public String getApplicationName() {
        return cachedAppName;
    }

    @Override
    public String getSessionId() {
        return sessionId;
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
     * Applies a state payload pushed from a native WinRT change handler.
     * Called on WinRT threads: cache updates happen inline (cheap, in-memory)
     * while listener notifications are dispatched to the executor. Serialized
     * because WinRT may fire handlers concurrently on its thread pool.
     */
    synchronized void applyPushedSnapshot(String[] payload) {
        if (closed || payload == null || payload.length < 12) {
            return;
        }
        applySnapshot(payload);
    }

    /** Polling-mode refresh: pulls the snapshot from the bridge. */
    private void refreshNow() {
        if (closed) {
            return;
        }
        try {
            String[] payload = WinRtBridge.nativeGetSessionSnapshot(sessionId);
            if (payload == null || payload.length < 12) {
                return;
            }
            applySnapshot(payload);
        } catch (Exception e) {
            logger.debug("Error refreshing session {}: {}", sessionId, e.getMessage());
        }
    }

    private void applySnapshot(String[] payload) {
        String appName = payload[0];
        if (appName != null && !appName.isBlank()) {
            cachedAppName = appName;
        }
        boolean active = Boolean.parseBoolean(payload[1]);
        PlaybackState state = parseState(payload[2]);
        MediaSnapshot media = MediaSnapshot.fromPayload(payload);

        controls.updatePlaybackState(state);
        controls.updateCapabilities(payload[3]);

        PlaybackState oldState = cachedState;
        MediaSnapshot oldMedia = cachedMedia;
        Boolean oldActive = lastActive;

        cachedState = state;
        cachedActive = active;
        lastActive = active;
        cachedMedia = media;
        anchor = media == null
                ? PositionAnchor.EMPTY
                : new PositionAnchor(media.positionMs, System.nanoTime(), state, media.playbackRate());

        if (!eventDrivenEnabled || closed) {
            return;
        }
        boolean stateChanged = state != oldState;
        boolean activeChanged = oldActive == null || active != oldActive;
        boolean mediaChanged = !sameMedia(oldMedia, media);
        if (!stateChanged && !activeChanged && !mediaChanged) {
            return;
        }
        // Pushed snapshots arrive on WinRT threads; user listeners run on the
        // executor so they can never block native event delivery.
        try {
            executor.execute(() -> {
                if (closed) {
                    return;
                }
                if (stateChanged) {
                    listeners.forEach(listener -> listener.onPlaybackStateChanged(this, state));
                }
                if (activeChanged) {
                    listeners.forEach(listener -> listener.onSessionActiveChanged(this, active));
                }
                if (mediaChanged) {
                    Optional<NowPlaying> now = getNowPlaying();
                    listeners.forEach(listener -> listener.onNowPlayingChanged(this, now));
                }
            });
        } catch (Exception ignored) {
            // Executor already shut down.
        }
    }

    private void emitPositionTick() {
        if (closed || listeners.isEmpty() || cachedState != PlaybackState.PLAYING) {
            return;
        }
        MediaSnapshot media = cachedMedia;
        if (media == null || media.positionMs == null) {
            return;
        }
        Optional<NowPlaying> now = getNowPlaying();
        listeners.forEach(listener -> listener.onNowPlayingChanged(this, now));
    }

    private Long currentPositionMs(MediaSnapshot media) {
        if (!positionUpdatesEnabled) {
            return null;
        }
        PositionAnchor current = anchor;
        Long projected = current.projectMs(System.nanoTime());
        if (projected == null) {
            return null;
        }
        if (media.durationMs != null && media.durationMs > 0 && projected > media.durationMs) {
            return media.durationMs;
        }
        return projected;
    }

    private static PlaybackState parseState(String code) {
        if ("0".equals(code)) {
            return PlaybackState.PLAYING;
        }
        if ("1".equals(code)) {
            return PlaybackState.PAUSED;
        }
        if ("2".equals(code)) {
            return PlaybackState.STOPPED;
        }
        return PlaybackState.UNKNOWN;
    }

    private static boolean sameMedia(MediaSnapshot a, MediaSnapshot b) {
        if (a == null || b == null) {
            return a == b;
        }
        return Objects.equals(a.title, b.title)
                && Objects.equals(a.artist, b.artist)
                && Objects.equals(a.album, b.album)
                && Objects.equals(a.artwork, b.artwork)
                && Objects.equals(a.durationMs, b.durationMs)
                && a.live == b.live
                && normalizeMetadataForComparison(a.metadataPairs)
                        .equals(normalizeMetadataForComparison(b.metadataPairs));
    }

    private static String normalizeMetadataForComparison(String metadata) {
        if (metadata == null || metadata.isBlank()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (String line : metadata.split("\\R")) {
            if (line == null || line.isBlank()) {
                continue;
            }
            int sep = line.indexOf('=');
            if (sep <= 0) {
                out.append(line).append('\n');
                continue;
            }
            String key = line.substring(0, sep).trim();
            if (isVolatileMetadataKey(key)) {
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private static boolean isVolatileMetadataKey(String key) {
        return "timelineRawPositionMs".equals(key)
                || "timelineLastUpdatedTicks".equals(key)
                || "timelineNowTicks".equals(key)
                || "playbackRate".equals(key)
                || "playbackStatus".equals(key);
    }

    /**
     * Immutable media fields from one native snapshot (no position; the
     * position is projected from {@link PositionAnchor} at read time).
     */
    private static final class MediaSnapshot {
        final String title;
        final String artist;
        final String album;
        final String artwork;
        final Long durationMs;
        final Long positionMs;
        final boolean live;
        final String metadataPairs;

        private MediaSnapshot(String title, String artist, String album, String artwork,
                              Long durationMs, Long positionMs, boolean live, String metadataPairs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.live = live;
            this.metadataPairs = metadataPairs == null ? "" : metadataPairs;
        }

        static MediaSnapshot fromPayload(String[] payload) {
            String title = emptyToNull(payload[4]);
            String artist = emptyToNull(payload[5]);
            String album = emptyToNull(payload[6]);
            String artwork = emptyToNull(payload[7]);
            Long durationMs = parseLong(payload[8]);
            if (title == null && artist == null && album == null && artwork == null && durationMs == null) {
                return null;
            }
            return new MediaSnapshot(
                    title,
                    artist,
                    album,
                    artwork,
                    durationMs,
                    parseLong(payload[9]),
                    Boolean.parseBoolean(payload[10]),
                    payload[11]);
        }

        double playbackRate() {
            for (String line : metadataPairs.split("\\R")) {
                if (line == null || !line.startsWith("playbackRate=")) {
                    continue;
                }
                String value = line.substring("playbackRate=".length()).trim();
                if (value.isEmpty() || "null".equalsIgnoreCase(value)) {
                    return 1.0d;
                }
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException ignored) {
                    return 1.0d;
                }
            }
            return 1.0d;
        }

        WindowsNowPlaying toNowPlaying(Long positionMsNow) {
            String[] payload = new String[] {
                    title,
                    artist,
                    album,
                    artwork,
                    durationMs == null ? null : String.valueOf(durationMs),
                    positionMsNow == null ? null : String.valueOf(positionMsNow),
                    String.valueOf(live),
                    metadataPairs.isBlank() ? null : metadataPairs
            };
            return new WindowsNowPlaying(payload);
        }

        private static String emptyToNull(String value) {
            return value == null || value.isBlank() ? null : value;
        }

        private static Long parseLong(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
    }

    /**
     * Immutable position anchor: a known position at a monotonic timestamp,
     * projected forward while playing.
     */
    private static final class PositionAnchor {
        static final PositionAnchor EMPTY =
                new PositionAnchor(null, System.nanoTime(), PlaybackState.UNKNOWN, 1.0d);

        final Long positionMs;
        final long monotonicNanos;
        final PlaybackState state;
        final double rate;

        PositionAnchor(Long positionMs, long monotonicNanos, PlaybackState state, double rate) {
            this.positionMs = positionMs;
            this.monotonicNanos = monotonicNanos;
            this.state = state;
            this.rate = rate;
        }

        Long projectMs(long nowNanos) {
            if (positionMs == null) {
                return null;
            }
            if (state != PlaybackState.PLAYING || rate <= 0.0d) {
                return positionMs;
            }
            long elapsedNanos = Math.max(0L, nowNanos - monotonicNanos);
            long deltaMs = Math.max(0L, Math.round((elapsedNanos / 1_000_000.0d) * rate));
            return positionMs + deltaMs;
        }
    }
}
