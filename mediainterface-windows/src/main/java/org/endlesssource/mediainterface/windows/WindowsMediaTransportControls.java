package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.TransportCapabilities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;

final class WindowsMediaTransportControls implements MediaTransportControls {
    private static final Logger logger = LoggerFactory.getLogger(WindowsMediaTransportControls.class);
    private static final TransportCapabilities DEFAULT_CAPABILITIES =
            new TransportCapabilities(true, true, true, true, true, true);

    private final String sessionId;
    private volatile PlaybackState cachedPlaybackState = PlaybackState.UNKNOWN;
    private volatile TransportCapabilities cachedCapabilities = DEFAULT_CAPABILITIES;

    WindowsMediaTransportControls(String sessionId) {
        this.sessionId = sessionId;
    }

    @Override
    public boolean play() {
        boolean ok = WinRtBridge.nativePlay(sessionId);
        if (!ok) logger.debug("Play command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean pause() {
        boolean ok = WinRtBridge.nativePause(sessionId);
        if (!ok) logger.debug("Pause command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean togglePlayPause() {
        boolean ok = WinRtBridge.nativeTogglePlayPause(sessionId);
        if (!ok) logger.debug("Toggle play/pause command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean next() {
        boolean ok = WinRtBridge.nativeNext(sessionId);
        if (!ok) logger.debug("Next command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean previous() {
        boolean ok = WinRtBridge.nativePrevious(sessionId);
        if (!ok) logger.debug("Previous command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean stop() {
        boolean ok = WinRtBridge.nativeStop(sessionId);
        if (!ok) logger.debug("Stop command failed for session {}", sessionId);
        return ok;
    }

    @Override
    public boolean seek(Duration position) {
        if (position == null || position.isNegative()) {
            logger.debug("Rejecting seek with invalid position for session {}", sessionId);
            return false;
        }
        boolean ok = WinRtBridge.nativeSeek(sessionId, position.toMillis());
        if (!ok) logger.debug("Seek command failed for session {} at {}", sessionId, position);
        return ok;
    }

    @Override
    public PlaybackState getPlaybackState() {
        return cachedPlaybackState;
    }

    PlaybackState refreshPlaybackState() {
        int code = WinRtBridge.nativeGetPlaybackState(sessionId);
        cachedPlaybackState = switch (code) {
            case 0 -> PlaybackState.PLAYING;
            case 1 -> PlaybackState.PAUSED;
            case 2 -> PlaybackState.STOPPED;
            default -> PlaybackState.UNKNOWN;
        };
        return cachedPlaybackState;
    }

    @Override
    public TransportCapabilities getCapabilities() {
        return cachedCapabilities;
    }

    TransportCapabilities refreshCapabilities() {
        boolean[] caps = WinRtBridge.nativeGetCapabilities(sessionId);
        if (caps == null || caps.length < 6) {
            logger.debug("Falling back to default capabilities for session {}", sessionId);
            cachedCapabilities = DEFAULT_CAPABILITIES;
            return cachedCapabilities;
        }
        cachedCapabilities = new TransportCapabilities(caps[0], caps[1], caps[2], caps[3], caps[4], caps[5]);
        return cachedCapabilities;
    }
}
