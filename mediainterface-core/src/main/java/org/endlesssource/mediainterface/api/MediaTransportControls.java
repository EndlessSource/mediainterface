package org.endlesssource.mediainterface.api;

import java.time.Duration;

/**
 * Transport controls for media playback
 */
public interface MediaTransportControls {

    /**
     * Play the current media
     * @return true if the command was sent successfully
     */
    boolean play();

    /**
     * Pause the current media
     * @return true if the command was sent successfully
     */
    boolean pause();

    /**
     * Toggle between play and pause
     * @return true if the command was sent successfully
     */
    boolean togglePlayPause();

    /**
     * Skip to the next track
     * @return true if the command was sent successfully
     */
    boolean next();

    /**
     * Go to the previous track
     * @return true if the command was sent successfully
     */
    boolean previous();

    /**
     * Stop playback
     * @return true if the command was sent successfully
     */
    boolean stop();

    /**
     * Seek to a specific position
     * @param position The position to seek to
     * @return true if the command was sent successfully
     */
    boolean seek(Duration position);

    /**
     * Get the current playback state
     * @return The current playback state
     */
    PlaybackState getPlaybackState();

    /**
     * Get supported transport controls for this session
     * @return Set of supported controls
     */
    TransportCapabilities getCapabilities();
}

