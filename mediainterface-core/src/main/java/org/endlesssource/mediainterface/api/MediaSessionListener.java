package org.endlesssource.mediainterface.api;

/**
 * Listener for media session events
 */
public interface MediaSessionListener {

    /**
     * Called when now playing information changes
     * @param session The session that changed
     * @param nowPlaying The new now playing info (empty if none)
     */
    default void onNowPlayingChanged(MediaSession session, java.util.Optional<NowPlaying> nowPlaying) {}

    /**
     * Called when playback state changes
     * @param session The session that changed
     * @param state The new playback state
     */
    default void onPlaybackStateChanged(MediaSession session, PlaybackState state) {}

    /**
     * Called when a session becomes active/inactive
     * @param session The session that changed
     * @param active Whether the session is now active
     */
    default void onSessionActiveChanged(MediaSession session, boolean active) {}

    /**
     * Called when a session is removed
     * @param sessionId The ID of the removed session
     */
    default void onSessionRemoved(String sessionId) {}

    /**
     * Called when a new session is added
     * @param session The new session
     */
    default void onSessionAdded(MediaSession session) {}
}
