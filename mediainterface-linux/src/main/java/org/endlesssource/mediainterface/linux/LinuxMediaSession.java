package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.*;
import org.freedesktop.dbus.connections.impl.DBusConnection;
import org.freedesktop.dbus.exceptions.DBusException;
import org.freedesktop.dbus.interfaces.Properties;
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
    private static final long POSITION_CORRECTION_TOLERANCE_MS = 1500L;
    private static final double RATE_EPSILON = 0.0001d;

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
    private volatile Optional<NowPlaying> cachedNowPlaying = Optional.empty();
    private volatile boolean cachedActive;
    private final String applicationName;

    private NowPlaying lastNowPlaying;
    private PlaybackState lastState = PlaybackState.UNKNOWN;
    private String anchorTrackKey;
    private Optional<Duration> anchorPosition = Optional.empty();
    private long anchorMonotonicNanos = System.nanoTime();
    private PlaybackState anchorState = PlaybackState.UNKNOWN;
    private double anchorRate = 1.0d;

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
        this.executor = Executors.newSingleThreadScheduledExecutor();
        this.applicationName = resolveApplicationName();

        // Warm cache immediately so first reads/listener registration see current state.
        checkForChanges();
        // Continue background refresh for cached snapshots.
        startMonitoring();
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
        return applicationName;
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

    private void startMonitoring() {
        executor.scheduleWithFixedDelay(this::checkForChanges, 0L, updateIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void checkForChanges() {
        if (closed) {
            return;
        }

        try {
            PlaybackState currentState = controls.refreshPlaybackState();
            Optional<NowPlaying> currentNowPlaying = queryNowPlaying(currentState);

            cachedNowPlaying = currentNowPlaying;
            cachedActive = computeActive(currentState, currentNowPlaying);

            if (eventDrivenEnabled) {
                if (currentState != lastState) {
                    lastState = currentState;
                    listeners.forEach(listener -> listener.onPlaybackStateChanged(this, currentState));
                }

                if (currentNowPlaying.isPresent()) {
                    NowPlaying current = currentNowPlaying.get();
                    if (lastNowPlaying == null
                            || !sameMedia(lastNowPlaying, current)
                            || !sameEventPosition(lastNowPlaying, current, currentState)) {
                        lastNowPlaying = current;
                        listeners.forEach(listener -> listener.onNowPlayingChanged(this, Optional.of(current)));
                    }
                } else if (lastNowPlaying != null) {
                    lastNowPlaying = null;
                    listeners.forEach(listener -> listener.onNowPlayingChanged(this, Optional.empty()));
                }
            }
        } catch (Exception e) {
            logger.debug("Error checking for changes in {}: {}", getApplicationName(), e.getMessage());
        }
    }

    public void close() {
        closed = true;
        listeners.clear();
        executor.shutdownNow();
    }

    private Optional<NowPlaying> queryNowPlaying(PlaybackState currentState) {
        try {
            Object metadata = properties.Get("org.mpris.MediaPlayer2.Player", "Metadata");
            Optional<Map<String, Object>> metadataMap = MprisMetadataUtils.toMetadataMap(metadata);
            return metadataMap
                    .map(map -> new LinuxNowPlaying(map, player, properties))
                    .map(nowPlaying -> withAnchoredPosition(nowPlaying, currentState));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private NowPlaying withAnchoredPosition(LinuxNowPlaying nowPlaying, PlaybackState state) {
        long nowMonotonicNanos = System.nanoTime();
        Optional<Duration> rawPosition = nowPlaying.getPosition();
        double rate = readPlaybackRate().orElse(1.0d);
        String trackKey = trackKey(nowPlaying);
        boolean trackChanged = !Objects.equals(trackKey, anchorTrackKey);

        Optional<Duration> predictedBefore = projectedAnchorPosition(nowMonotonicNanos);

        if (trackChanged) {
            anchorTrackKey = trackKey;
            if (rawPosition.isPresent()) {
                anchorPosition = rawPosition;
            } else {
                anchorPosition = Optional.empty();
            }
            anchorMonotonicNanos = nowMonotonicNanos;
            anchorState = state;
            anchorRate = rate;
        } else if (rawPosition.isPresent()) {
            boolean shouldAnchorToRaw = shouldAnchorToRaw(rawPosition.get(), predictedBefore, state, rate);
            if (shouldAnchorToRaw) {
                anchorPosition = rawPosition;
                anchorMonotonicNanos = nowMonotonicNanos;
            }
            anchorState = state;
            anchorRate = rate;
        } else if (state != anchorState || Math.abs(rate - anchorRate) > RATE_EPSILON) {
            anchorPosition = predictedBefore;
            anchorMonotonicNanos = nowMonotonicNanos;
            anchorState = state;
            anchorRate = rate;
        }

        Optional<Duration> computedPosition = projectedAnchorPosition(nowMonotonicNanos);
        if (computedPosition.isEmpty()) {
            computedPosition = rawPosition;
        }

        Optional<Duration> duration = nowPlaying.getDuration();
        if (computedPosition.isPresent() && duration.isPresent()
                && computedPosition.get().compareTo(duration.get()) > 0) {
            computedPosition = duration;
        }

        if (Objects.equals(computedPosition, rawPosition)) {
            return nowPlaying;
        }
        return new PositionedNowPlaying(nowPlaying, computedPosition, Instant.now());
    }

    private boolean shouldAnchorToRaw(Duration rawPosition,
                                      Optional<Duration> predictedBefore,
                                      PlaybackState state,
                                      double rate) {
        if (anchorPosition.isEmpty()) {
            return true;
        }
        if (state != anchorState || Math.abs(rate - anchorRate) > RATE_EPSILON) {
            return true;
        }
        if (predictedBefore.isEmpty()) {
            return true;
        }

        long diffMillis = Math.abs(rawPosition.minus(predictedBefore.get()).toMillis());
        if (diffMillis > POSITION_CORRECTION_TOLERANCE_MS) {
            return true;
        }

        // Avoid backward jitter from coarse/stale Position values while playing.
        return !isBackward(rawPosition, predictedBefore.get(), state);
    }

    private static boolean isBackward(Duration rawPosition, Duration predictedPosition, PlaybackState state) {
        return state == PlaybackState.PLAYING && rawPosition.compareTo(predictedPosition) < 0;
    }

    private Optional<Duration> projectedAnchorPosition(long nowMonotonicNanos) {
        if (anchorPosition.isEmpty()) {
            return Optional.empty();
        }
        if (anchorState != PlaybackState.PLAYING || anchorRate <= 0.0d) {
            return anchorPosition;
        }

        long elapsedNanos = Math.max(0L, nowMonotonicNanos - anchorMonotonicNanos);
        long deltaNanos = Math.max(0L, Math.round(elapsedNanos * anchorRate));
        return Optional.of(anchorPosition.get().plusNanos(deltaNanos));
    }

    private Optional<Double> readPlaybackRate() {
        try {
            return Optional.of(player.getRate());
        } catch (Exception e) {
            logger.debug("Failed to get playback rate via direct method for {}: {}", busName, e.getMessage());
        }

        try {
            Object rateObj = properties.Get("org.mpris.MediaPlayer2.Player", "Rate");
            Object unwrapped = MprisMetadataUtils.unwrap(rateObj);
            if (unwrapped instanceof Number number) {
                return Optional.of(number.doubleValue());
            }
        } catch (Exception e) {
            logger.debug("Failed to get playback rate via properties for {}: {}", busName, e.getMessage());
        }

        return Optional.empty();
    }

    private boolean computeActive(PlaybackState state, Optional<NowPlaying> nowPlaying) {
        if (state == PlaybackState.PLAYING) {
            return true;
        }
        if (state == PlaybackState.UNKNOWN) {
            return nowPlaying
                    .flatMap(NowPlaying::getTitle)
                    .map(title -> !title.isEmpty())
                    .orElse(false);
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

    private static boolean sameEventPosition(NowPlaying a, NowPlaying b, PlaybackState currentState) {
        if (currentState == PlaybackState.PLAYING) {
            long aMs = a.getPosition().map(Duration::toMillis).orElse(-1L);
            long bMs = b.getPosition().map(Duration::toMillis).orElse(-1L);
            return aMs == bMs;
        }
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
