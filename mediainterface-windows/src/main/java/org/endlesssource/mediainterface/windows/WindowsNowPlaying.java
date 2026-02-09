package org.endlesssource.mediainterface.windows;

import org.endlesssource.mediainterface.api.NowPlaying;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class WindowsNowPlaying implements NowPlaying {
    private final Optional<String> title;
    private final Optional<String> artist;
    private final Optional<String> album;
    private final Optional<String> artwork;
    private final Optional<Duration> duration;
    private final Optional<Duration> position;
    private final boolean liveStream;
    private final Map<String, String> additionalMetadata;
    private final Instant lastUpdated;

    WindowsNowPlaying(String[] payload) {
        this.title = optional(payload, 0);
        this.artist = optional(payload, 1);
        this.album = optional(payload, 2);
        this.artwork = optional(payload, 3);
        this.duration = parseDuration(payload, 4);
        this.position = parseDuration(payload, 5);
        this.liveStream = parseBoolean(payload, 6);
        this.additionalMetadata = parseAdditionalMetadata(payload, 7);
        this.lastUpdated = Instant.now();
    }

    @Override
    public Optional<String> getTitle() {
        return title;
    }

    @Override
    public Optional<String> getArtist() {
        return artist;
    }

    @Override
    public Optional<String> getAlbum() {
        return album;
    }

    @Override
    public Optional<String> getArtwork() {
        return artwork;
    }

    @Override
    public Optional<Duration> getDuration() {
        return duration;
    }

    @Override
    public Optional<Duration> getPosition() {
        return position;
    }

    @Override
    public Map<String, String> getAdditionalMetadata() {
        return additionalMetadata;
    }

    @Override
    public boolean isLiveStream() {
        return liveStream;
    }

    @Override
    public Instant getLastUpdated() {
        return lastUpdated;
    }

    private static Optional<String> optional(String[] payload, int index) {
        if (payload == null || payload.length <= index) {
            return Optional.empty();
        }
        String value = payload[index];
        return value == null || value.isBlank() ? Optional.empty() : Optional.of(value);
    }

    private static Optional<Duration> parseDuration(String[] payload, int index) {
        Optional<String> value = optional(payload, index);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        try {
            long millis = Long.parseLong(value.get());
            if (millis < 0) {
                return Optional.empty();
            }
            return Optional.of(Duration.ofMillis(millis));
        } catch (NumberFormatException ignored) {
            return Optional.empty();
        }
    }

    private static boolean parseBoolean(String[] payload, int index) {
        return optional(payload, index).map(Boolean::parseBoolean).orElse(false);
    }

    private static Map<String, String> parseAdditionalMetadata(String[] payload, int index) {
        Optional<String> encoded = optional(payload, index);
        if (encoded.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> out = new HashMap<>();
        for (String line : encoded.get().split("\\R")) {
            int sep = line.indexOf('=');
            if (sep <= 0 || sep >= line.length() - 1) {
                continue;
            }
            String key = line.substring(0, sep).trim();
            String value = line.substring(sep + 1).trim();
            if (!key.isEmpty() && !value.isEmpty()) {
                out.put(key, value);
            }
        }
        return Collections.unmodifiableMap(out);
    }
}
