package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

final class MacOsPerlMediaSession implements MediaSession {
    private static final String SESSION_ID = "system";

    private final MacOsPerlAdapter adapter;
    private final MacOsPerlMediaTransportControls controls;
    private final boolean eventDrivenEnabled;
    private final long updateIntervalMs;
    private final ScheduledExecutorService executor;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private volatile Optional<NowPlaying> cachedNowPlaying = Optional.empty();
    private volatile boolean cachedActive;
    private volatile String cachedApplicationName = "System";
    private PlaybackState lastState = PlaybackState.UNKNOWN;
    private Snapshot lastSnapshot;
    private Boolean lastActive;

    MacOsPerlMediaSession(MacOsPerlAdapter adapter, boolean eventDrivenEnabled, Duration updateInterval) {
        this.adapter = adapter;
        this.controls = new MacOsPerlMediaTransportControls(adapter);
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.updateIntervalMs = updateInterval.toMillis();
        this.executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(this::checkForChanges, 0L, updateIntervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public Optional<NowPlaying> getNowPlaying() {
        return cachedNowPlaying;
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
        executor.shutdownNow();
    }

    private void checkForChanges() {
        if (closed) {
            return;
        }
        MacOsPerlAdapter.Snapshot adapterSnapshot = adapter.get();
        PlaybackState state = toPlaybackState(adapterSnapshot.playingRaw());
        controls.updatePlaybackState(state);
        cachedActive = adapterSnapshot.active();

        String app = adapterSnapshot.app();
        cachedApplicationName = (app == null || app.isBlank()) ? "System" : app;

        Optional<NowPlaying> now = toNowPlaying(adapterSnapshot);
        cachedNowPlaying = now;
        Snapshot snap = now.map(Snapshot::fromNowPlaying).orElseGet(Snapshot::empty);
        Snapshot previousSnapshot = lastSnapshot;

        if (eventDrivenEnabled) {
            if (state != lastState) {
                lastState = state;
                listeners.forEach(l -> l.onPlaybackStateChanged(this, state));
            }

            boolean active = cachedActive;
            if (lastActive == null || active != lastActive) {
                lastActive = active;
                listeners.forEach(l -> l.onSessionActiveChanged(this, active));
            }

            if (!snap.equals(previousSnapshot)) {
                listeners.forEach(l -> l.onNowPlayingChanged(this, now));
            }
        }
        lastSnapshot = snap;
    }

    private Optional<NowPlaying> toNowPlaying(MacOsPerlAdapter.Snapshot snapshot) {
        if (!snapshot.active()) {
            return Optional.empty();
        }
        Long effectiveDurationMs = effectiveDurationMs(snapshot);
        String[] payload = new String[] {
                snapshot.title(),
                snapshot.artist(),
                snapshot.album(),
                snapshot.artwork(),
                effectiveDurationMs == null ? null : String.valueOf(effectiveDurationMs),
                snapshot.positionMs() == null ? null : String.valueOf(snapshot.positionMs()),
                "false",
                ""
        };
        return Optional.of(new MacOsNowPlaying(payload));
    }

    private Long effectiveDurationMs(MacOsPerlAdapter.Snapshot snapshot) {
        if (snapshot.durationMs() != null) {
            return snapshot.durationMs();
        }
        if (lastSnapshot == null || lastSnapshot.durationMs().isEmpty()) {
            return null;
        }
        boolean sameTrackIdentity = Objects.equals(snapshot.title(), lastSnapshot.title().orElse(null))
                && Objects.equals(snapshot.artist(), lastSnapshot.artist().orElse(null))
                && Objects.equals(snapshot.album(), lastSnapshot.album().orElse(null));
        return sameTrackIdentity ? lastSnapshot.durationMs().get() : null;
    }

    private static PlaybackState toPlaybackState(String playingRaw) {
        return switch (playingRaw) {
            case "1" -> PlaybackState.PLAYING;
            case "0" -> PlaybackState.PAUSED;
            default -> PlaybackState.UNKNOWN;
        };
    }

    private record Snapshot(Optional<String> title,
                            Optional<String> artist,
                            Optional<String> album,
                            Optional<Long> durationMs,
                            Optional<Long> positionSec) {
        static Snapshot fromNowPlaying(NowPlaying now) {
            return new Snapshot(
                    now.getTitle(),
                    now.getArtist(),
                    now.getAlbum(),
                    now.getDuration().map(Duration::toMillis),
                    now.getPosition().map(Duration::getSeconds)
            );
        }

        static Snapshot empty() {
            return new Snapshot(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        }
    }
}
