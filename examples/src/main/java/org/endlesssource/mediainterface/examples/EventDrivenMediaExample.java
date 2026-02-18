package org.endlesssource.mediainterface.examples;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.MediaSessionListener;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

public final class EventDrivenMediaExample {
    private static final Logger logger = LoggerFactory.getLogger(EventDrivenMediaExample.class);

    public static void main(String[] args) {
        PlatformSupport support = SystemMediaFactory.getCurrentPlatformSupport();
        if (!support.available()) {
            logger.warn("Unsupported platform: {}", SystemMediaFactory.getPlatformName());
            logger.warn("Reason: {}", support.reason());
            return;
        }

        SystemMediaOptions options = SystemMediaOptions.defaults()
                .withEventDrivenEnabled(true)
                .withSessionPollInterval(Duration.ofMillis(200))
                .withSessionUpdateInterval(Duration.ofMillis(200));

        try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface(options)) {
            logger.info("Event-driven example running (listener callbacks enabled). Press Ctrl+C to stop.");

            MediaSessionListener sessionListener = new MediaSessionListener() {
                @Override
                public void onSessionAdded(MediaSession session) {
                    logger.info("Session added: {} ({})", session.getApplicationName(), session.getSessionId());
                    session.addListener(this);
                }

                @Override
                public void onSessionRemoved(String sessionId) {
                    logger.info("Session removed: {}", sessionId);
                }

                @Override
                public void onPlaybackStateChanged(MediaSession session, PlaybackState state) {
                    logger.info("State changed [{}]: {}", session.getApplicationName(), state);
                }

                @Override
                public void onNowPlayingChanged(MediaSession session, Optional<NowPlaying> nowPlaying) {
                    String title = nowPlaying.flatMap(NowPlaying::getTitle).orElse("Unknown Title");
                    String artist = nowPlaying.flatMap(NowPlaying::getArtist).orElse("Unknown Artist");
                    String position = nowPlaying.flatMap(NowPlaying::getPosition)
                            .map(EventDrivenMediaExample::formatDuration)
                            .orElse("--:--");
                    String duration = nowPlaying.flatMap(NowPlaying::getDuration)
                            .map(EventDrivenMediaExample::formatDuration)
                            .orElse("--:--");
                    logger.info("Now playing [{}]: {} - {} ({}/{})",
                            session.getApplicationName(), title, artist, position, duration);
                }
            };

            media.addSessionListener(sessionListener);
            media.getAllSessions().forEach(session -> session.addListener(sessionListener));

            while (true) {
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("Event-driven example failed", e);
        }
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private EventDrivenMediaExample() {
    }
}
