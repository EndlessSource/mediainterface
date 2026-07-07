package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The system-wide macOS media session.
 *
 * <p>State is kept fresh by a single long-lived adapter stream process that
 * emits a snapshot on every MediaRemote change notification (plus a slow
 * heartbeat). If streaming is unavailable, the session falls back to polling
 * the one-shot adapter at the configured interval. The playback position is
 * projected from the last snapshot's timestamp at read time.</p>
 */
final class MacOsPerlMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(MacOsPerlMediaSession.class);
    private static final String SESSION_ID = "system";
    private static final long STREAM_SUPERVISOR_INTERVAL_MS = 5000L;
    private static final long STREAM_RESTART_BACKOFF_MS = 2000L;

    private final MacOsPerlAdapter adapter;
    private final MacOsPerlMediaTransportControls controls;
    private final boolean eventDrivenEnabled;
    private final boolean positionUpdatesEnabled;
    private final long updateIntervalMs;
    private final ScheduledExecutorService executor;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final List<ScheduledFuture<?>> scheduledTasks = new CopyOnWriteArrayList<>();
    private volatile boolean closed;

    private volatile MacOsPerlAdapter.StreamHandle streamHandle;
    private volatile long lastStreamStartMillis;

    private volatile MacOsPerlAdapter.Snapshot cachedSnapshot = MacOsPerlAdapter.Snapshot.empty();
    private volatile boolean cachedActive;
    private volatile String cachedApplicationName = "System";
    private volatile PlaybackState lastState = PlaybackState.UNKNOWN;
    private volatile MediaKey lastMediaKey;
    private volatile Boolean lastActive;

    MacOsPerlMediaSession(MacOsPerlAdapter adapter, SystemMediaOptions options, ScheduledExecutorService executor) {
        this.adapter = adapter;
        this.controls = new MacOsPerlMediaTransportControls(adapter);
        this.eventDrivenEnabled = options.isEventDrivenEnabled();
        this.positionUpdatesEnabled = options.isPositionUpdatesEnabled();
        this.updateIntervalMs = options.getSessionUpdateInterval().toMillis();
        this.executor = executor;

        // Warm cache synchronously so first reads/listener registration see
        // current state; the stream's initial snapshot arrives asynchronously.
        applySnapshot(adapter.get());

        boolean streaming = startStream();
        if (streaming) {
            // The stream keeps the cache fresh; supervise and restart it if
            // the adapter process dies.
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    this::superviseStream,
                    STREAM_SUPERVISOR_INTERVAL_MS, STREAM_SUPERVISOR_INTERVAL_MS, TimeUnit.MILLISECONDS));
        } else {
            // Fallback: poll the one-shot adapter like before.
            logger.warn("MediaRemote stream unavailable; falling back to polling every {} ms", updateIntervalMs);
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    () -> applySnapshot(adapter.get()),
                    updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
        }

        if (eventDrivenEnabled && positionUpdatesEnabled) {
            // Pure-CPU tick emitting position progression while playing.
            scheduledTasks.add(executor.scheduleWithFixedDelay(
                    this::emitPositionTick, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS));
        }
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        return toNowPlaying(cachedSnapshot);
    }

    @Override
    public MediaTransportControls getControls() {
        return controls;
    }

    @Override
    public String getApplicationName() {
        return cachedApplicationName;
    }

    @Override
    public String getSessionId() {
        return SESSION_ID;
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
        MacOsPerlAdapter.StreamHandle handle = streamHandle;
        if (handle != null) {
            handle.close();
        }
    }

    private boolean startStream() {
        if (closed) {
            return false;
        }
        lastStreamStartMillis = System.currentTimeMillis();
        Optional<MacOsPerlAdapter.StreamHandle> handle = adapter.startStream(
                this::applySnapshot,
                this::onStreamExit);
        streamHandle = handle.orElse(null);
        return handle.isPresent();
    }

    private void onStreamExit() {
        if (closed) {
            return;
        }
        logger.info("MediaRemote adapter stream exited; scheduling restart");
        try {
            executor.schedule(() -> {
                if (!closed && (streamHandle == null || !streamHandle.isAlive())) {
                    startStream();
                }
            }, STREAM_RESTART_BACKOFF_MS, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // Executor already shut down.
        }
    }

    private void superviseStream() {
        if (closed) {
            return;
        }
        MacOsPerlAdapter.StreamHandle handle = streamHandle;
        if (handle == null || !handle.isAlive()) {
            // Guard against a missed onExit callback.
            if (System.currentTimeMillis() - lastStreamStartMillis > STREAM_RESTART_BACKOFF_MS) {
                startStream();
            }
        }
    }

    // Synchronized: snapshots arrive from the stream reader thread, the
    // executor (fallback polling) and the constructor.
    private synchronized void applySnapshot(MacOsPerlAdapter.Snapshot snapshot) {
        if (closed || snapshot == null) {
            return;
        }
        PlaybackState state = toPlaybackState(snapshot.playingRaw());
        controls.updatePlaybackState(state);

        MacOsPerlAdapter.Snapshot effective = withCarriedDuration(snapshot, cachedSnapshot);
        boolean active = effective.active();

        PlaybackState oldState = lastState;
        MediaKey oldKey = lastMediaKey;
        Boolean oldActive = lastActive;

        cachedSnapshot = effective;
        cachedActive = active;
        String app = effective.app();
        cachedApplicationName = (app == null || app.isBlank()) ? "System" : app;
        lastState = state;
        lastActive = active;
        MediaKey newKey = MediaKey.of(effective, state);
        lastMediaKey = newKey;

        if (!eventDrivenEnabled) {
            return;
        }
        if (state != oldState) {
            listeners.forEach(l -> l.onPlaybackStateChanged(this, state));
        }
        if (oldActive == null || active != oldActive) {
            listeners.forEach(l -> l.onSessionActiveChanged(this, active));
        }
        if (!newKey.equals(oldKey)) {
            Optional<NowPlaying> now = getNowPlaying();
            listeners.forEach(l -> l.onNowPlayingChanged(this, now));
        }
    }

    /**
     * Some players omit the duration in intermediate updates; carry the
     * previous duration forward while the track identity is unchanged.
     */
    private static MacOsPerlAdapter.Snapshot withCarriedDuration(MacOsPerlAdapter.Snapshot snapshot,
                                                                 MacOsPerlAdapter.Snapshot previous) {
        if (snapshot.durationMs() != null || previous == null || previous.durationMs() == null) {
            return snapshot;
        }
        boolean sameTrack = Objects.equals(snapshot.title(), previous.title())
                && Objects.equals(snapshot.artist(), previous.artist())
                && Objects.equals(snapshot.album(), previous.album());
        if (!sameTrack) {
            return snapshot;
        }
        return snapshot.withDurationMs(previous.durationMs());
    }

    private void emitPositionTick() {
        if (closed || listeners.isEmpty() || lastState != PlaybackState.PLAYING || !cachedActive) {
            return;
        }
        Optional<NowPlaying> now = getNowPlaying();
        listeners.forEach(l -> l.onNowPlayingChanged(this, now));
    }

    private Optional<NowPlaying> toNowPlaying(MacOsPerlAdapter.Snapshot snapshot) {
        if (snapshot == null || !snapshot.active()) {
            return Optional.empty();
        }
        Long positionMs = positionUpdatesEnabled
                ? snapshot.positionMsAt(System.currentTimeMillis())
                : null;
        String[] payload = new String[] {
                snapshot.title(),
                snapshot.artist(),
                snapshot.album(),
                snapshot.artwork(),
                snapshot.durationMs() == null ? null : String.valueOf(snapshot.durationMs()),
                positionMs == null ? null : String.valueOf(positionMs),
                "false",
                ""
        };
        return Optional.of(new MacOsNowPlaying(payload));
    }

    private static PlaybackState toPlaybackState(String playingRaw) {
        if ("1".equals(playingRaw)) {
            return PlaybackState.PLAYING;
        }
        if ("0".equals(playingRaw)) {
            return PlaybackState.PAUSED;
        }
        return PlaybackState.UNKNOWN;
    }

    /**
     * Identity used to decide whether an onNowPlayingChanged event should
     * fire: media fields plus second-granularity position (so seeks while
     * paused still produce an event, but projection ticks do not double-fire).
     */
    private static final class MediaKey {
        private final String title;
        private final String artist;
        private final String album;
        private final String artwork;
        private final Long durationMs;
        private final Long positionSec;

        private MediaKey(String title, String artist, String album, String artwork,
                         Long durationMs, Long positionSec) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artwork = artwork;
            this.durationMs = durationMs;
            this.positionSec = positionSec;
        }

        static MediaKey of(MacOsPerlAdapter.Snapshot snapshot, PlaybackState state) {
            Long positionMs = snapshot.positionMsAt(System.currentTimeMillis());
            // While playing, the projection tick reports progression; only
            // track identity changes matter for the change event.
            Long positionSec = state == PlaybackState.PLAYING || positionMs == null
                    ? null
                    : positionMs / 1000L;
            return new MediaKey(snapshot.title(), snapshot.artist(), snapshot.album(),
                    snapshot.artwork(), snapshot.durationMs(), positionSec);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof MediaKey)) {
                return false;
            }
            MediaKey other = (MediaKey) obj;
            return Objects.equals(title, other.title)
                    && Objects.equals(artist, other.artist)
                    && Objects.equals(album, other.album)
                    && Objects.equals(artwork, other.artwork)
                    && Objects.equals(durationMs, other.durationMs)
                    && Objects.equals(positionSec, other.positionSec);
        }

        @Override
        public int hashCode() {
            return Objects.hash(title, artist, album, artwork, durationMs, positionSec);
        }
    }
}
