package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.TransportCapabilities;

import java.time.Duration;

final class MacOsPerlMediaTransportControls implements MediaTransportControls {
    private static final TransportCapabilities CAPS =
            new TransportCapabilities(true, true, true, true, true, true);

    private final MacOsPerlAdapter adapter;
    private volatile PlaybackState cachedPlaybackState = PlaybackState.UNKNOWN;

    MacOsPerlMediaTransportControls(MacOsPerlAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public boolean play() {
        return adapter.play();
    }

    @Override
    public boolean pause() {
        return adapter.pause();
    }

    @Override
    public boolean togglePlayPause() {
        return adapter.toggle();
    }

    @Override
    public boolean next() {
        return adapter.next();
    }

    @Override
    public boolean previous() {
        return adapter.previous();
    }

    @Override
    public boolean stop() {
        return adapter.stop();
    }

    @Override
    public boolean seek(Duration position) {
        return adapter.seek(position);
    }

    @Override
    public PlaybackState getPlaybackState() {
        return cachedPlaybackState;
    }

    @Override
    public TransportCapabilities getCapabilities() {
        return CAPS;
    }

    void updatePlaybackState(PlaybackState state) {
        cachedPlaybackState = state == null ? PlaybackState.UNKNOWN : state;
    }
}
