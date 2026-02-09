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

    private final String sessionId;
    private final boolean eventDrivenEnabled;
    private final long updateIntervalMs;
    private final WindowsMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private volatile boolean closed;

    private volatile PlaybackState lastPlaybackState = PlaybackState.UNKNOWN;
    private volatile Snapshot lastSnapshot;
    private volatile Boolean lastActive;

    WindowsMediaSession(String sessionId, boolean eventDrivenEnabled, Duration updateInterval) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.updateIntervalMs = Objects.requireNonNull(updateInterval, "updateInterval").toMillis();
        this.controls = new WindowsMediaTransportControls(sessionId);
        this.executor = eventDrivenEnabled ? Executors.newSingleThreadScheduledExecutor() : null;
        if (eventDrivenEnabled) {
            executor.scheduleWithFixedDelay(this::checkForChanges, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public synchronized Optional<NowPlaying> getNowPlaying() {
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
        String appName = WinRtBridge.nativeGetSessionAppName(sessionId);
        if (appName == null || appName.isBlank()) {
            return sessionId;
        }
        return appName;
    }

    @Override
    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean isActive() {
        return WinRtBridge.nativeIsSessionActive(sessionId);
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
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void checkForChanges() {
        if (closed || !eventDrivenEnabled) {
            return;
        }
        try {
            PlaybackState currentState = controls.getPlaybackState();
            if (currentState != lastPlaybackState) {
                lastPlaybackState = currentState;
                listeners.forEach(listener -> listener.onPlaybackStateChanged(this, currentState));
            }

            boolean active = isActive();
            if (lastActive == null || active != lastActive) {
                lastActive = active;
                listeners.forEach(listener -> listener.onSessionActiveChanged(this, active));
            }

            Optional<NowPlaying> currentNowPlaying = getNowPlaying();
            Snapshot snapshot = currentNowPlaying.map(Snapshot::fromNowPlaying).orElseGet(() -> Snapshot.fromPayload(null));
            if (!snapshot.sameMedia(lastSnapshot)) {
                lastSnapshot = snapshot;
                listeners.forEach(listener -> listener.onNowPlayingChanged(this, currentNowPlaying));
            }
        } catch (Exception e) {
            logger.debug("Error checking session changes for {}: {}", sessionId, e.getMessage());
        }
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

        boolean sameMedia(Snapshot other) {
            if (other == null) {
                return false;
            }
            return title.equals(other.title)
                    && artist.equals(other.artist)
                    && album.equals(other.album)
                    && artwork.equals(other.artwork)
                    && durationMs.equals(other.durationMs)
                    && positionBucket(positionMs) == positionBucket(other.positionMs)
                    && live == other.live
                    && metadataPairs.equals(other.metadataPairs);
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
    }

}
