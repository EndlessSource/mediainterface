package org.endlesssource.mediainterface.api;

import java.util.Optional;

/**
 * Represents a single media session (e.g., Spotify, Chrome tab, iTunes)
 */
public interface MediaSession {

    /**
     * Get information about currently playing media
     * @return Optional containing media info, or empty if none
     */
    Optional<NowPlaying> getNowPlaying();

    /**
     * Get the media transport controls for this session
     * @return The transport controls
     */
    MediaTransportControls getControls();

    /**
     * Get the application name that owns this session
     * @return Application name (e.g., "Spotify", "Chrome", "iTunes")
     */
    String getApplicationName();

    /**
     * Get the session ID (platform-specific identifier)
     * @return Unique session identifier
     */
    String getSessionId();

    /**
     * Check if this session is currently active/focused
     * @return true if this is the active media session
     */
    boolean isActive();

    /**
     * Add a listener for changes to this session
     * @param listener The listener to add
     */
    void addListener(MediaSessionListener listener);

    /**
     * Remove a listener from this session
     * @param listener The listener to remove
     */
    void removeListener(MediaSessionListener listener);
}
