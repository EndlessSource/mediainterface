package org.endlesssource.mediainterface.macos;

import org.endlesssource.mediainterface.api.MediaTransportControls;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.TransportCapabilities;

import java.time.Duration;

final class MacOsPerlMediaTransportControls implements MediaTransportControls {
    private static final TransportCapabilities CAPS =
            new TransportCapabilities(true, true, true, true, true, true);

    private final MacOsPerlAdapter adapter;

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
        MacOsPerlAdapter.Snapshot s = adapter.get();
        return switch (s.playingRaw()) {
            case "1" -> PlaybackState.PLAYING;
            case "0" -> PlaybackState.PAUSED;
            default -> PlaybackState.UNKNOWN;
        };
    }

    @Override
    public TransportCapabilities getCapabilities() {
        return CAPS;
    }
}
