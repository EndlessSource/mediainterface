package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.TransportCapabilities;
import org.freedesktop.dbus.ObjectPath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * MPRIS transport controls. Commands are sent directly over D-Bus; playback
 * state and capabilities are pushed in by the owning session, which keeps them
 * fresh from PropertiesChanged signals and periodic refreshes, so reads here
 * never block on the bus.
 */
class LinuxMediaTransportControls implements MediaTransportControls {
    private static final Logger logger = LoggerFactory.getLogger(LinuxMediaTransportControls.class);

    private static final TransportCapabilities DEFAULT_CAPABILITIES =
            new TransportCapabilities(true, true, true, true, true, false);

    private final MprisPlayer player;
    private final Supplier<Optional<ObjectPath>> trackIdSupplier;
    private volatile PlaybackState cachedState = PlaybackState.UNKNOWN;
    private volatile TransportCapabilities capabilities = DEFAULT_CAPABILITIES;

    LinuxMediaTransportControls(MprisPlayer player, Supplier<Optional<ObjectPath>> trackIdSupplier) {
        this.player = player;
        this.trackIdSupplier = trackIdSupplier;
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
            Optional<ObjectPath> trackId = trackIdSupplier.get();
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

    @Override
    public TransportCapabilities getCapabilities() {
        return capabilities;
    }

    void updatePlaybackState(PlaybackState state) {
        cachedState = state == null ? PlaybackState.UNKNOWN : state;
    }

    void updateCapabilities(TransportCapabilities newCapabilities) {
        if (newCapabilities != null) {
            capabilities = newCapabilities;
        }
    }
}
