package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

class LinuxMediaSession implements MediaSession {
    private static final Logger logger = LoggerFactory.getLogger(LinuxMediaSession.class);

    private final DBusConnection connection;
    private final String busName;
    private final MprisMediaPlayer2 mediaPlayer2;
    private final MprisPlayer player;
    private final Properties properties;
    private final LinuxMediaTransportControls controls;
    private final List<MediaSessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService executor;
    private final boolean eventDrivenEnabled;
    private final long updateIntervalMs;
    private volatile boolean closed;

    private NowPlaying lastNowPlaying;
    private PlaybackState lastState = PlaybackState.UNKNOWN;
    private String lastTrackKey;
    private Optional<Duration> lastRawPosition = Optional.empty();
    private Optional<Duration> lastVirtualPosition = Optional.empty();
    private long lastPositionSampleMs = System.currentTimeMillis();

    public LinuxMediaSession(DBusConnection connection,
                             String busName,
                             boolean eventDrivenEnabled,
                             java.time.Duration updateInterval) throws DBusException {
        this.connection = connection;
        this.busName = busName;
        this.mediaPlayer2 = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", MprisMediaPlayer2.class);
        this.player = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", MprisPlayer.class);
        this.properties = connection.getRemoteObject(busName, "/org/mpris/MediaPlayer2", Properties.class);
        this.controls = new LinuxMediaTransportControls(player, properties);
        this.eventDrivenEnabled = eventDrivenEnabled;
        this.updateIntervalMs = updateInterval.toMillis();
        this.executor = eventDrivenEnabled ? Executors.newSingleThreadScheduledExecutor() : null;

        // Start monitoring for changes
        startMonitoring();
    }

    @Override
    public synchronized Optional<NowPlaying> getNowPlaying() {
        try {
            Object metadata = properties.Get("org.mpris.MediaPlayer2.Player", "Metadata");
            Optional<Map<String, Object>> metadataMap = MprisMetadataUtils.toMetadataMap(metadata);
            return metadataMap
                    .map(map -> new LinuxNowPlaying(map, player, properties))
                    .map(this::withVirtualizedPositionIfNeeded);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public MediaTransportControls getControls() {
        return controls;
    }

    @Override
    public String getApplicationName() {
        try {
            String identity = mediaPlayer2.getIdentity();
            return identity != null ? identity : extractAppNameFromBusName();
        } catch (Exception e) {
            return extractAppNameFromBusName();
        }
    }

    private String extractAppNameFromBusName() {
        // org.mpris.MediaPlayer2.spotify -> spotify
        String[] parts = busName.split("\\.");
        if (parts.length >= 4) {
            return capitalizeFirst(parts[3]);
        }
        return busName;
    }

    private String capitalizeFirst(String str) {
        if (str == null || str.isEmpty()) return str;
        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    @Override
    public String getSessionId() {
        return busName;
    }

    @Override
    public boolean isActive() {
        PlaybackState state = controls.getPlaybackState();
        if (state == PlaybackState.PLAYING) {
            return true;
        }

        // For implementations that don't provide playback state (like Firefox),
        // consider it active if there's metadata available
        if (state == PlaybackState.UNKNOWN) {
            Optional<NowPlaying> nowPlaying = getNowPlaying();
            return nowPlaying.isPresent() &&
                   nowPlaying.get().getTitle().isPresent() &&
                   !nowPlaying.get().getTitle().get().isEmpty();
        }

        return false;
    }

    @Override
    public void addListener(MediaSessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(MediaSessionListener listener) {
        listeners.remove(listener);
    }

    private void startMonitoring() {
        // Check for changes every 0.5 seconds
        if (!eventDrivenEnabled) {
            return;
        }
        executor.scheduleWithFixedDelay(this::checkForChanges, updateIntervalMs, updateIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkForChanges() {
        if (closed) {
            return;
        }

        try {
            // Check playback state changes
            PlaybackState currentState = controls.getPlaybackState();
            if (currentState != lastState) {
                lastState = currentState;
                listeners.forEach(listener -> listener.onPlaybackStateChanged(this, currentState));
            }

            // Check now playing changes
            Optional<NowPlaying> currentNowPlaying = getNowPlaying();
            if (currentNowPlaying.isPresent()) {
                NowPlaying current = currentNowPlaying.get();
                if (lastNowPlaying == null
                        || !sameMedia(lastNowPlaying, current)
                        || !samePositionBucket(lastNowPlaying, current)) {
                    lastNowPlaying = current;
                    listeners.forEach(listener -> listener.onNowPlayingChanged(this, Optional.of(current)));
                }
            } else if (lastNowPlaying != null) {
                lastNowPlaying = null;
                listeners.forEach(listener -> listener.onNowPlayingChanged(this, Optional.empty()));
            }
        } catch (Exception e) {
            logger.debug("Error checking for changes in {}: {}", getApplicationName(), e.getMessage());
        }
    }

    public void close() {
        closed = true;
        listeners.clear();
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private NowPlaying withVirtualizedPositionIfNeeded(LinuxNowPlaying nowPlaying) {
        long nowMs = System.currentTimeMillis();
        String trackKey = trackKey(nowPlaying);
        boolean trackChanged = !Objects.equals(trackKey, lastTrackKey);

        PlaybackState state = controls.getPlaybackState();
        Optional<Duration> rawPosition = nowPlaying.getPosition();
        Optional<Duration> virtualPosition = rawPosition;

        if (trackChanged) {
            lastTrackKey = trackKey;
        } else if (state == PlaybackState.PLAYING) {
            if (rawPosition.isPresent()) {
                if (lastRawPosition.isPresent() && lastVirtualPosition.isPresent()
                        && rawPosition.get().equals(lastRawPosition.get())) {
                    long elapsedMs = Math.max(0L, nowMs - lastPositionSampleMs);
                    virtualPosition = Optional.of(lastVirtualPosition.get().plusMillis(elapsedMs));
                }
            } else if (lastVirtualPosition.isPresent()) {
                long elapsedMs = Math.max(0L, nowMs - lastPositionSampleMs);
                virtualPosition = Optional.of(lastVirtualPosition.get().plusMillis(elapsedMs));
            }
        } else if (rawPosition.isEmpty() && lastVirtualPosition.isPresent()) {
            // Keep last known position stable while paused/stopped when source omits position.
            virtualPosition = lastVirtualPosition;
        }

        Optional<Duration> duration = nowPlaying.getDuration();
        if (virtualPosition.isPresent() && duration.isPresent()
                && virtualPosition.get().compareTo(duration.get()) > 0) {
            virtualPosition = duration;
        }

        lastRawPosition = rawPosition;
        lastVirtualPosition = virtualPosition;
        lastPositionSampleMs = nowMs;

        if (Objects.equals(virtualPosition, rawPosition)) {
            return nowPlaying;
        }
        return new PositionedNowPlaying(nowPlaying, virtualPosition, Instant.ofEpochMilli(nowMs));
    }

    private static String trackKey(NowPlaying nowPlaying) {
        return nowPlaying.getTitle().orElse("") + "|" +
               nowPlaying.getArtist().orElse("") + "|" +
               nowPlaying.getAlbum().orElse("") + "|" +
               nowPlaying.getDuration().map(Duration::toMillis).orElse(-1L);
    }

    private static boolean sameMedia(NowPlaying a, NowPlaying b) {
        return a.getTitle().equals(b.getTitle()) &&
               a.getArtist().equals(b.getArtist()) &&
               a.getAlbum().equals(b.getAlbum()) &&
               a.getArtwork().equals(b.getArtwork()) &&
               a.getDuration().equals(b.getDuration());
    }

    private static boolean samePositionBucket(NowPlaying a, NowPlaying b) {
        long aSec = a.getPosition().map(Duration::getSeconds).orElse(-1L);
        long bSec = b.getPosition().map(Duration::getSeconds).orElse(-1L);
        return aSec == bSec;
    }

    private static final class PositionedNowPlaying implements NowPlaying {
        private final NowPlaying delegate;
        private final Optional<Duration> position;
        private final Instant lastUpdated;

        private PositionedNowPlaying(NowPlaying delegate, Optional<Duration> position, Instant lastUpdated) {
            this.delegate = delegate;
            this.position = position;
            this.lastUpdated = lastUpdated;
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
        public Optional<Duration> getPosition() { return position; }

        @Override
        public Map<String, String> getAdditionalMetadata() { return delegate.getAdditionalMetadata(); }

        @Override
        public boolean isLiveStream() { return delegate.isLiveStream(); }

        @Override
        public Instant getLastUpdated() { return lastUpdated; }
    }
}
