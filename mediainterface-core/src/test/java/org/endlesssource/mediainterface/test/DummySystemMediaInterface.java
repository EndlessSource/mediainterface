package org.endlesssource.mediainterface.test;

import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.TransportCapabilities;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DummySystemMediaInterface implements SystemMediaInterface {
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final MediaSession session = new DummyMediaSession();

    @Override
    public Optional<MediaSession> getActiveSession() {
        return Optional.of(session);
    }

    @Override
    public List<MediaSession> getAllSessions() {
        return List.of(session);
    }

    @Override
    public Optional<MediaSession> getSessionByApp(String appName) {
        if ("Dummy Player".equalsIgnoreCase(appName)) {
            return Optional.of(session);
        }
        return Optional.empty();
    }

    @Override
    public boolean hasActiveSessions() {
        return true;
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
        return true;
    }

    @Override
    public void close() {
        listeners.clear();
    }

    private static final class DummyMediaSession implements MediaSession {
        private final MediaTransportControls controls = new DummyControls();

        @Override
        public Optional<NowPlaying> getNowPlaying() {
            return Optional.of(new DummyNowPlaying());
        }

        @Override
        public MediaTransportControls getControls() {
            return controls;
        }

        @Override
        public String getApplicationName() {
            return "Dummy Player";
        }

        @Override
        public String getSessionId() {
            return "dummy-session";
        }

        @Override
        public boolean isActive() {
            return true;
        }

        @Override
        public void addListener(MediaSessionListener listener) {
            // no-op test implementation
        }

        @Override
        public void removeListener(MediaSessionListener listener) {
            // no-op test implementation
        }
    }

    private static final class DummyControls implements MediaTransportControls {
        @Override
        public boolean play() { return true; }

        @Override
        public boolean pause() { return true; }

        @Override
        public boolean togglePlayPause() { return true; }

        @Override
        public boolean next() { return true; }

        @Override
        public boolean previous() { return true; }

        @Override
        public boolean stop() { return true; }

        @Override
        public boolean seek(Duration position) { return position != null && !position.isNegative(); }

        @Override
        public PlaybackState getPlaybackState() { return PlaybackState.PLAYING; }

        @Override
        public TransportCapabilities getCapabilities() {
            return new TransportCapabilities(true, true, true, true, true, true);
        }
    }

    private static final class DummyNowPlaying implements NowPlaying {
        @Override
        public Optional<String> getTitle() { return Optional.of("Dummy Song"); }

        @Override
        public Optional<String> getArtist() { return Optional.of("Dummy Artist"); }

        @Override
        public Optional<String> getAlbum() { return Optional.of("Dummy Album"); }

        @Override
        public Optional<String> getArtwork() { return Optional.empty(); }

        @Override
        public Optional<Duration> getDuration() { return Optional.of(Duration.ofMinutes(3)); }

        @Override
        public Optional<Duration> getPosition() { return Optional.of(Duration.ofSeconds(30)); }

        @Override
        public Map<String, String> getAdditionalMetadata() { return Map.of("genre", "test"); }

        @Override
        public boolean isLiveStream() { return false; }

        @Override
        public Instant getLastUpdated() { return Instant.now(); }
    }
}

