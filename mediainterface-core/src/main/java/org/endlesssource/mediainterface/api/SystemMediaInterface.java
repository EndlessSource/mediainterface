package org.endlesssource.mediainterface.api;

import java.util.List;
import java.util.Optional;

/**
 * The main entry point for system media control
 * Provides unified access to system media transport controls
 */
public interface SystemMediaInterface extends AutoCloseable {

    /**
     * Get the currently active media session
     * @return Optional containing the active session, or empty if none
     */
    Optional<MediaSession> getActiveSession();

    /**
     * Get all available media sessions
     * @return List of all media sessions (Spotify, Chrome, iTunes, etc.)
     */
    List<MediaSession> getAllSessions();

    /**
     * Find a specific media session by application name
     * @param appName The application name (e.g., "Spotify", "Chrome")
     * @return Optional containing the session if found
     */
    Optional<MediaSession> getSessionByApp(String appName);

    /**
     * Check if any media sessions are available
     * @return true if at least one media session exists
     */
    boolean hasActiveSessions();

    /**
     * Add a listener for media session changes
     * @param listener The listener to add
     */
    void addSessionListener(MediaSessionListener listener);

    /**
     * Remove a session listener
     * @param listener The listener to remove
     */
    void removeSessionListener(MediaSessionListener listener);

    /**
     * Whether event-driven updates are enabled for this instance.
     * Implementations may fall back to polling to generate events.
     * @return true if event-driven flow is enabled
     */
    boolean isEventDrivenEnabled();

    /**
     * Close and release resources held by this interface.
     */
    @Override
    void close();
}
