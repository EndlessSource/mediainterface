package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.TransportCapabilities;
import org.freedesktop.dbus.ObjectPath;
import org.freedesktop.dbus.interfaces.Properties;
import org.freedesktop.dbus.types.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

class LinuxMediaTransportControls implements MediaTransportControls {
    private static final Logger logger = LoggerFactory.getLogger(LinuxMediaTransportControls.class);

    private final MprisPlayer player;
    private final Properties properties;
    private TransportCapabilities capabilities;
    private volatile PlaybackState cachedState = PlaybackState.UNKNOWN;

    public LinuxMediaTransportControls(MprisPlayer player, Properties properties) {
        this.player = player;
        this.properties = properties;
        updateCapabilities();
    }

    @Override
    public boolean play() {
        try {
            player.Play();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to play: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean pause() {
        try {
            player.Pause();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to pause: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean togglePlayPause() {
        try {
            player.PlayPause();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to toggle play/pause: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean next() {
        try {
            player.Next();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to skip to next: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean previous() {
        try {
            player.Previous();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to go to previous: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean stop() {
        try {
            player.Stop();
            return true;
        } catch (Exception e) {
            logger.warn("Failed to stop: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean seek(Duration position) {
        try {
            Optional<ObjectPath> trackId = getCurrentTrackId();
            if (trackId.isEmpty()) {
                logger.debug("Cannot seek because current track id is unavailable");
                return false;
            }
            // MPRIS expects microseconds
            player.SetPosition(trackId.get(), position.toNanos() / 1000);
            return true;
        } catch (Exception e) {
            logger.warn("Failed to seek: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public PlaybackState getPlaybackState() {
        return cachedState;
    }

    PlaybackState refreshPlaybackState() {
        PlaybackState currentState = PlaybackState.UNKNOWN;

        // Try a direct method first (more reliable)
        try {
            String status = player.getPlaybackStatus();
            if (status != null) {
                currentState = parsePlaybackStatus(status);
                return cacheAndReturn(currentState);
            }
        } catch (Exception e) {
            logger.debug("Failed to get playback state via direct method: {}", e.getMessage());
        }

        // Fallback to properties interface - handle different return types
        try {
            Object statusObj = properties.Get("org.mpris.MediaPlayer2.Player", "PlaybackStatus");
            String status = null;

            if (statusObj instanceof Variant<?> statusVariant) {
                if (statusVariant.getValue() instanceof String statusStr) {
                    status = statusStr;
                }
            } else if (statusObj instanceof String statusStr) {
                status = statusStr;
            }

            if (status != null) {
                currentState = parsePlaybackStatus(status);
                return cacheAndReturn(currentState);
            }
        } catch (Exception e) {
            logger.debug("Failed to get playback state via properties: {}", e.getMessage());
        }

        // For some MPRIS implementations (like Firefox), we might not be able to get playback state
        // In such cases, we'll rely on the active state check in the session
        return cachedState != null ? cachedState : PlaybackState.UNKNOWN;
    }

    private PlaybackState cacheAndReturn(PlaybackState state) {
        cachedState = state == null ? PlaybackState.UNKNOWN : state;
        return cachedState;
    }

    @Override
    public TransportCapabilities getCapabilities() {
        if (capabilities == null) {
            updateCapabilities();
        }
        return capabilities;
    }

    private void updateCapabilities() {
        try {
            // Try direct method calls first (more reliable)
            boolean canPlay = getDirectBooleanCapability(() -> player.getCanPlay()).orElse(
                    getBooleanProperty("CanPlay").orElse(true));
            boolean canPause = getDirectBooleanCapability(() -> player.getCanPause()).orElse(
                    getBooleanProperty("CanPause").orElse(true));
            boolean canNext = getDirectBooleanCapability(() -> player.getCanGoNext()).orElse(
                    getBooleanProperty("CanGoNext").orElse(true));
            boolean canPrevious = getDirectBooleanCapability(() -> player.getCanGoPrevious()).orElse(
                    getBooleanProperty("CanGoPrevious").orElse(true));
            boolean canSeek = getDirectBooleanCapability(() -> player.getCanSeek()).orElse(
                    getBooleanProperty("CanSeek").orElse(true));
            boolean canStop = getDirectBooleanCapability(() -> player.getCanControl()).orElse(
                    getBooleanProperty("CanControl").orElse(true));

            capabilities = new TransportCapabilities(canPlay, canPause, canNext, canPrevious, canStop, canSeek);
        } catch (Exception e) {
            logger.warn("Failed to get capabilities, using defaults: {}", e.getMessage());
            // Default to basic capabilities
            capabilities = new TransportCapabilities(true, true, true, true, true, false);
        }
    }

    private Optional<Boolean> getBooleanProperty(String propertyName) {
        try {
            Variant<?> variant = properties.Get("org.mpris.MediaPlayer2.Player", propertyName);
            if (variant != null && variant.getValue() instanceof Boolean value) {
                return Optional.of(value);
            }
        } catch (Exception e) {
            logger.debug("Failed to get boolean property {}: {}", propertyName, e.getMessage());
        }
        return Optional.empty();
    }

    private Optional<Boolean> getDirectBooleanCapability(Supplier<Boolean> supplier) {
        try {
            return Optional.of(supplier.get());
        } catch (Exception e) {
            logger.debug("Failed to get capability via direct method: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private PlaybackState parsePlaybackStatus(String status) {
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

    private Optional<ObjectPath> getCurrentTrackId() {
        try {
            Variant<?> metadataVariant = properties.Get("org.mpris.MediaPlayer2.Player", "Metadata");
            Optional<Map<String, Object>> metadata = MprisMetadataUtils.toMetadataMap(metadataVariant);
            return metadata
                    .map(map -> map.get("mpris:trackid"))
                    .flatMap(value -> {
                        Object unwrapped = MprisMetadataUtils.unwrap(value);
                        if (unwrapped instanceof ObjectPath path) {
                            return Optional.of(path);
                        }
                        return Optional.empty();
                    });
        } catch (Exception e) {
            logger.debug("Failed to resolve current track id: {}", e.getMessage());
        }
        return Optional.empty();
    }
}
