package org.endlesssource.mediainterface.examples;

import org.endlesssource.mediainterface.PlatformSupport;
import org.endlesssource.mediainterface.SystemMediaFactory;
import org.endlesssource.mediainterface.api.MediaSession;
import org.endlesssource.mediainterface.api.NowPlaying;
import org.endlesssource.mediainterface.api.SystemMediaInterface;
import org.endlesssource.mediainterface.api.SystemMediaOptions;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Scanner;

public final class MediaControlCliExample {

    public static void main(String[] args) {
        PlatformSupport support = SystemMediaFactory.getCurrentPlatformSupport();
        if (!support.available()) {
            System.out.println("Unsupported platform: " + SystemMediaFactory.getPlatformName());
            System.out.println("Reason: " + support.reason());
            return;
        }

        SystemMediaOptions options = SystemMediaOptions.defaults()
                .withEventDrivenEnabled(true)
                .withSessionPollInterval(Duration.ofMillis(200))
                .withSessionUpdateInterval(Duration.ofMillis(200));

        try (SystemMediaInterface media = SystemMediaFactory.createSystemInterface(options);
             Scanner scanner = new Scanner(System.in)) {
            System.out.println("Media Control CLI");
            printHelp();

            MediaSession selected = media.getActiveSession().orElse(null);

            while (true) {
                if (selected == null || !containsSession(media.getAllSessions(), selected.getSessionId())) {
                    selected = media.getActiveSession().orElse(null);
                }

                System.out.print("media> ");
                if (!scanner.hasNextLine()) {
                    break;
                }

                String line = scanner.nextLine().trim();
                if (line.isEmpty()) {
                    continue;
                }

                String[] parts = line.split("\\s+", 2);
                String cmd = parts[0].toLowerCase();
                String arg = parts.length > 1 ? parts[1].trim() : "";

                switch (cmd) {
                    case "help" -> printHelp();
                    case "quit", "exit" -> {
                        return;
                    }
                    case "list" -> printSessions(media.getAllSessions(), selected);
                    case "active" -> {
                        selected = media.getActiveSession().orElse(null);
                        printSelected(selected);
                    }
                    case "select" -> selected = selectSessionByIndex(media.getAllSessions(), arg, selected);
                    case "info" -> printNowPlaying(selected);
                    case "play" -> runControl(selected, "play", s -> s.getControls().play());
                    case "pause" -> runControl(selected, "pause", s -> s.getControls().pause());
                    case "toggle" -> runControl(selected, "toggle", s -> s.getControls().togglePlayPause());
                    case "next" -> runControl(selected, "next", s -> s.getControls().next());
                    case "prev", "previous" -> runControl(selected, "previous", s -> s.getControls().previous());
                    case "stop" -> runControl(selected, "stop", s -> s.getControls().stop());
                    case "seek" -> runSeek(selected, arg);
                    default -> System.out.println("Unknown command: " + cmd + " (type 'help')");
                }
            }
        } catch (Exception e) {
            System.err.println("Media control CLI failed: " + e.getMessage());
        }
    }

    private static void printHelp() {
        System.out.println("Commands:");
        System.out.println("  help                Show this help");
        System.out.println("  list                List available sessions");
        System.out.println("  active              Select current active session");
        System.out.println("  select <index>      Select session by index from list");
        System.out.println("  info                Show selected session now playing");
        System.out.println("  play|pause|toggle   Playback controls");
        System.out.println("  next|prev|stop      Playback controls");
        System.out.println("  seek <mm:ss|sec>    Seek to position");
        System.out.println("  exit                Quit");
    }

    private static void printSessions(List<MediaSession> sessions, MediaSession selected) {
        if (sessions.isEmpty()) {
            System.out.println("No sessions.");
            return;
        }
        for (int i = 0; i < sessions.size(); i++) {
            MediaSession session = sessions.get(i);
            String marker = selected != null && selected.getSessionId().equals(session.getSessionId()) ? "*" : " ";
            System.out.printf("%s [%d] %s (%s)%n", marker, i, session.getApplicationName(), session.getSessionId());
        }
    }

    private static MediaSession selectSessionByIndex(List<MediaSession> sessions, String arg, MediaSession current) {
        if (arg.isEmpty()) {
            System.out.println("Usage: select <index>");
            return current;
        }
        try {
            int idx = Integer.parseInt(arg);
            if (idx < 0 || idx >= sessions.size()) {
                System.out.println("Invalid index.");
                return current;
            }
            MediaSession selected = sessions.get(idx);
            printSelected(selected);
            return selected;
        } catch (NumberFormatException e) {
            System.out.println("Invalid index.");
            return current;
        }
    }

    private static void printSelected(MediaSession selected) {
        if (selected == null) {
            System.out.println("No active session selected.");
            return;
        }
        System.out.println("Selected: " + selected.getApplicationName() + " (" + selected.getSessionId() + ")");
    }

    private static void printNowPlaying(MediaSession selected) {
        if (selected == null) {
            System.out.println("No session selected.");
            return;
        }
        Optional<NowPlaying> now = selected.getNowPlaying();
        if (now.isEmpty()) {
            System.out.println("No media info.");
            return;
        }
        NowPlaying n = now.get();
        String title = n.getTitle().orElse("Unknown Title");
        String artist = n.getArtist().orElse("Unknown Artist");
        String position = n.getPosition().map(MediaControlCliExample::formatDuration).orElse("--:--");
        String duration = n.getDuration().map(MediaControlCliExample::formatDuration).orElse("--:--");
        System.out.printf("%s - %s [%s/%s]%n", title, artist, position, duration);
    }

    private static void runControl(MediaSession selected, String name, ControlCall call) {
        if (selected == null) {
            System.out.println("No session selected.");
            return;
        }
        boolean ok = call.invoke(selected);
        System.out.println(name + ": " + (ok ? "ok" : "failed"));
    }

    private static void runSeek(MediaSession selected, String arg) {
        if (selected == null) {
            System.out.println("No session selected.");
            return;
        }
        if (arg.isEmpty()) {
            System.out.println("Usage: seek <mm:ss|sec>");
            return;
        }
        Optional<Duration> target = parseTime(arg);
        if (target.isEmpty()) {
            System.out.println("Invalid time. Use mm:ss or seconds.");
            return;
        }
        boolean ok = selected.getControls().seek(target.get());
        System.out.println("seek: " + (ok ? "ok" : "failed"));
    }

    private static Optional<Duration> parseTime(String value) {
        try {
            if (value.contains(":")) {
                String[] parts = value.split(":");
                if (parts.length != 2) {
                    return Optional.empty();
                }
                long minutes = Long.parseLong(parts[0]);
                long seconds = Long.parseLong(parts[1]);
                if (minutes < 0 || seconds < 0 || seconds >= 60) {
                    return Optional.empty();
                }
                return Optional.of(Duration.ofSeconds(minutes * 60 + seconds));
            }
            long seconds = Long.parseLong(value);
            if (seconds < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofSeconds(seconds));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.getSeconds());
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        return String.format("%d:%02d", minutes, seconds);
    }

    private static boolean containsSession(List<MediaSession> sessions, String sessionId) {
        for (MediaSession session : sessions) {
            if (session.getSessionId().equals(sessionId)) {
                return true;
            }
        }
        return false;
    }

    private interface ControlCall {
        boolean invoke(MediaSession session);
    }

    private MediaControlCliExample() {
    }
}
