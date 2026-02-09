package org.endlesssource.mediainterface.api;

import java.time.Duration;
import java.util.Optional;

/**
 * Information about currently playing media
 */
public interface NowPlaying {

    /**
     * Get the title of the current media
     * @return Optional containing title, or empty if unknown
     */
    Optional<String> getTitle();

    /**
     * Get the artist/author of the current media
     * @return Optional containing artist, or empty if unknown
     */
    Optional<String> getArtist();

    /**
     * Get the album name
     * @return Optional containing album, or empty if unknown
     */
    Optional<String> getAlbum();

    /**
     * Get the artwork/thumbnail URL or file path
     * @return Optional containing artwork location, or empty if none
     */
    Optional<String> getArtwork();

    /**
     * Get the total duration of the media
     * @return Optional containing duration, or empty if unknown
     */
    Optional<Duration> getDuration();

    /**
     * Get the current playback position
     * @return Optional containing position, or empty if unknown
     */
    Optional<Duration> getPosition();

    /**
     * Get additional metadata (genre, year, etc.)
     * @return Map of additional metadata
     */
    java.util.Map<String, String> getAdditionalMetadata();

    /**
     * Check if this media info is from a live stream
     * @return true if live stream, false otherwise
     */
    boolean isLiveStream();

    /**
     * Get the last time this info was updated
     * @return Timestamp of the last update
     */
    java.time.Instant getLastUpdated();
}

