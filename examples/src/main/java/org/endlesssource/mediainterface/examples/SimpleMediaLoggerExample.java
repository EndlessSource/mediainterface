package org.endlesssource.mediainterface.examples;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.PlaybackState;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Optional;

public final class SimpleMediaLoggerExample {
    private static final Logger logger = LoggerFactory.getLogger(SimpleMediaLoggerExample.class);

    public static void main(String[] args) {
        PlatformSupport support = SystemMediaFactory.getCurrentPlatformSupport();
        if (!support.available()) {
            throw new IllegalStateException("Platform unsupported");
        }

        try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface()) {
            while (true) {
                Optional<MediaSession> activeSession = media.getActiveSession();

                if (activeSession.isEmpty()) {
                    logger.info("no active session");
                    return;
                }

                MediaSession session = activeSession.get();
                PlaybackState state = session.getControls().getPlaybackState();
                Optional<NowPlaying> nowPlaying = session.getNowPlaying();

                String app = session.getApplicationName();
                String title = nowPlaying.flatMap(NowPlaying::getTitle).orElse("Unknown Title");
                String artist = nowPlaying.flatMap(NowPlaying::getArtist).orElse("Unknown Artist");
                String position = nowPlaying.flatMap(NowPlaying::getPosition)
                        .map(SimpleMediaLoggerExample::formatDuration)
                        .orElse("--:--");
                String duration = nowPlaying.flatMap(NowPlaying::getDuration)
                        .map(SimpleMediaLoggerExample::formatDuration)
                        .orElse("--:--");

                logger.info("[{}] {} - {} | {} | {}/{}", app, title, artist, state, position, duration);
                Thread.sleep(1000);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            System.exit(0);
        }
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private SimpleMediaLoggerExample() {
    }
}
