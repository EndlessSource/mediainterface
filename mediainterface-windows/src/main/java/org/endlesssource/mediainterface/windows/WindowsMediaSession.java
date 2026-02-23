package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class WindowsMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(WindowsMediaSession.class);
    private static final long POSITION_EVENT_TICK_MS = 200L;

    private final String sessionId;
    private final boolean eventDrivenEnabled;
    private final long updateIntervalMs;
    private final WindowsMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;
    private volatile Optional<NowPlaying> cachedNowPlaying = Optional.empty();
    private volatile boolean cachedActive;
    private volatile String cachedAppName;

    private volatile PlaybackState lastPlaybackState = PlaybackState.UNKNOWN;
    private volatile Snapshot lastSnapshot;
    private volatile Boolean lastActive;
    private volatile double lastPlaybackRate = 1.0d;
    private volatile long lastSnapshotMonotonicNanos = System.nanoTime();

    WindowsMediaSession(String sessionId, boolean eventDrivenEnabled, Duration updateInterval) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.updateIntervalMs = Objects.requireNonNull(updateInterval, "updateInterval").toMillis();
        this.controls = new WindowsMediaTransportControls(sessionId);
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.cachedAppName = sessionId;
        // Warm cache immediately so first reads/listener registration see current state.
        checkForChanges();
        executor.scheduleWithFixedDelay(this::checkForChanges, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS);
        if (eventDrivenEnabled) {
            executor.scheduleWithFixedDelay(this::emitProjectedPositionChanges,
                    POSITION_EVENT_TICK_MS, POSITION_EVENT_TICK_MS, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        return cachedNowPlaying;
    }

    private Optional<NowPlaying> queryNowPlayingFromNative() {
        String[] payload = WinRtBridge.nativeGetNowPlaying(sessionId);
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        Snapshot snapshot = Snapshot.fromPayload(payload);
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(snapshot.toNowPlaying());
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
        executor.shutdownNow();
    }

    private void checkForChanges() {
        if (closed) {
            return;
        }
        try {
            PlaybackState currentState = controls.refreshPlaybackState();
            controls.refreshCapabilities();

            String appName = WinRtBridge.nativeGetSessionAppName(sessionId);
            if (appName != null && !appName.isBlank()) {
                cachedAppName = appName;
            }

            boolean active = WinRtBridge.nativeIsSessionActive(sessionId);
            cachedActive = active;

            Optional<NowPlaying> currentNowPlaying = queryNowPlayingFromNative();
            cachedNowPlaying = currentNowPlaying;
            Snapshot snapshot = currentNowPlaying.map(Snapshot::fromNowPlaying).orElseGet(() -> Snapshot.fromPayload(null));
            lastPlaybackRate = snapshot.playbackRate();
            lastSnapshotMonotonicNanos = System.nanoTime();

            if (eventDrivenEnabled) {
                if (currentState != lastPlaybackState) {
                    lastPlaybackState = currentState;
                    listeners.forEach(listener -> listener.onPlaybackStateChanged(this, currentState));
                }

                if (lastActive == null || active != lastActive) {
                    lastActive = active;
                    listeners.forEach(listener -> listener.onSessionActiveChanged(this, active));
                }

                boolean includePositionChanges = currentState == PlaybackState.PLAYING;
                if (!snapshot.sameMedia(lastSnapshot, includePositionChanges)) {
                    lastSnapshot = snapshot;
                    listeners.forEach(listener -> listener.onNowPlayingChanged(this, currentNowPlaying));
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking session changes for {}: {}", sessionId, e.getMessage());
        }
    }

    private void emitProjectedPositionChanges() {
        if (closed || !eventDrivenEnabled || lastPlaybackState != PlaybackState.PLAYING) {
            return;
        }
        Snapshot base = lastSnapshot;
        if (base == null || base.positionMs().isEmpty()) {
            return;
        }

        long nowNanos = System.nanoTime();
        Snapshot projected = base.projectedTo(nowNanos, lastSnapshotMonotonicNanos, lastPlaybackRate);
        if (projected.sameMedia(base, true)) {
            return;
        }

        lastSnapshot = projected;
        lastSnapshotMonotonicNanos = nowNanos;
        cachedNowPlaying = Optional.of(projected.toNowPlaying());
        listeners.forEach(listener -> listener.onNowPlayingChanged(this, cachedNowPlaying));
    }

    private record Snapshot(Optional<String> title,
                            Optional<String> artist,
                            Optional<String> album,
                            Optional<String> artwork,
                            Optional<Long> durationMs,
                            Optional<Long> positionMs,
                            boolean live,
                            String metadataPairs) {
        static Snapshot fromPayload(String[] payload) {
            if (payload == null || payload.length == 0) {
                return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), false, "");
            }
            return new Snapshot(
                    optional(payload, 0),
                    optional(payload, 1),
                    optional(payload, 2),
                    optional(payload, 3),
                    parseLong(payload, 4),
                    parseLong(payload, 5),
                    optional(payload, 6).map(Boolean::parseBoolean).orElse(false),
                    optional(payload, 7).orElse("")
            );
        }

        static Snapshot fromNowPlaying(NowPlaying nowPlaying) {
            return new Snapshot(
                    nowPlaying.getTitle(),
                    nowPlaying.getArtist(),
                    nowPlaying.getAlbum(),
                    nowPlaying.getArtwork(),
                    nowPlaying.getDuration().map(Duration::toMillis),
                    nowPlaying.getPosition().map(Duration::toMillis),
                    nowPlaying.isLiveStream(),
                    encodeMetadata(nowPlaying.getAdditionalMetadata())
            );
        }

        boolean isEmpty() {
            return title.isEmpty() && artist.isEmpty() && album.isEmpty() && artwork.isEmpty() && durationMs.isEmpty();
        }

        boolean sameMedia(Snapshot other, boolean includePositionChanges) {
            if (other == null) {
                return false;
            }
            boolean samePosition = !includePositionChanges
                    || positionBucket(positionMs) == positionBucket(other.positionMs);
            String normalizedMetadata = normalizeMetadataForComparison(metadataPairs);
            String otherNormalizedMetadata = normalizeMetadataForComparison(other.metadataPairs);
            return title.equals(other.title)
                    && artist.equals(other.artist)
                    && album.equals(other.album)
                    && artwork.equals(other.artwork)
                    && durationMs.equals(other.durationMs)
                    && samePosition
                    && live == other.live
                    && normalizedMetadata.equals(otherNormalizedMetadata);
        }

        double playbackRate() {
            if (metadataPairs == null || metadataPairs.isBlank()) {
                return 1.0d;
            }
            for (String line : metadataPairs.split("\\R")) {
                if (line == null || line.isBlank() || !line.startsWith("playbackRate=")) {
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

        Snapshot projectedTo(long nowMonotonicNanos, long anchorMonotonicNanos, double playbackRate) {
            if (positionMs.isEmpty()) {
                return this;
            }
            if (playbackRate <= 0.0d) {
                return this;
            }
            long elapsedNanos = Math.max(0L, nowMonotonicNanos - anchorMonotonicNanos);
            long deltaMs = Math.max(0L, Math.round((elapsedNanos / 1_000_000.0d) * playbackRate));
            if (deltaMs <= 0L) {
                return this;
            }

            long current = positionMs.get();
            long next = current + deltaMs;
            if (durationMs.isPresent()) {
                next = Math.min(next, durationMs.get());
            }
            if (next == current) {
                return this;
            }
            return new Snapshot(title, artist, album, artwork, durationMs, Optional.of(next), live, metadataPairs);
        }

        WindowsNowPlaying toNowPlaying() {
            String[] payload = new String[] {
                    title.orElse(null),
                    artist.orElse(null),
                    album.orElse(null),
                    artwork.orElse(null),
                    durationMs.map(String::valueOf).orElse(null),
                    positionMs.map(String::valueOf).orElse(null),
                    String.valueOf(live),
                    metadataPairs.isBlank() ? null : metadataPairs
            };
            return new WindowsNowPlaying(payload);
        }

        private static Optional<String> optional(String[] payload, int index) {
            if (payload.length <= index || payload[index] == null || payload[index].isBlank()) {
                return Optional.empty();
            }
            return Optional.of(payload[index]);
        }

        private static Optional<Long> parseLong(String[] payload, int index) {
            Optional<String> value = optional(payload, index);
            if (value.isEmpty()) {
                return Optional.empty();
            }
            try {
                return Optional.of(Long.parseLong(value.get()));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }

        private static String encodeMetadata(Map<String, String> metadata) {
            if (metadata == null || metadata.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            metadata.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> out.append(entry.getKey()).append("=").append(entry.getValue()).append("\n"));
            return out.toString();
        }

        private static long positionBucket(Optional<Long> positionMs) {
            return positionMs.map(ms -> ms / 1000L).orElse(-1L);
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
    }

}
