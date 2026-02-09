package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.NowPlaying;
import org.freedesktop.dbus.interfaces.Properties;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

class LinuxNowPlaying implements NowPlaying {
    private final Map<String, Object> metadata;
    private final Instant lastUpdated;
    private final MprisPlayer player;
    private final Properties properties;

    public LinuxNowPlaying(Map<String, Object> metadata) {
        this.metadata = new HashMap<>(metadata);
        this.lastUpdated = Instant.now();
        this.player = null; // Legacy constructor
        this.properties = null;
    }

    public LinuxNowPlaying(Map<String, Object> metadata, MprisPlayer player) {
        this.metadata = new HashMap<>(metadata);
        this.lastUpdated = Instant.now();
        this.player = player;
        this.properties = null;
    }

    public LinuxNowPlaying(Map<String, Object> metadata, MprisPlayer player, Properties properties) {
        this.metadata = new HashMap<>(metadata);
        this.lastUpdated = Instant.now();
        this.player = player;
        this.properties = properties;
    }

    @Override
    public Optional<String> getTitle() {
        return getStringValue("xesam:title");
    }

    @Override
    public Optional<String> getArtist() {
        Optional<List<String>> artists = getStringList("xesam:artist");
        if (artists.isPresent() && !artists.get().isEmpty()) {
            return Optional.of(String.join(", ", artists.get()));
        }
        return getStringValue("xesam:albumArtist");
    }

    @Override
    public Optional<String> getAlbum() {
        return getStringValue("xesam:album");
    }

    @Override
    public Optional<String> getArtwork() {
        return getStringValue("mpris:artUrl");
    }

    @Override
    public Optional<Duration> getDuration() {
        Optional<Long> lengthOpt = getLongValue("mpris:length");
        if (lengthOpt.isPresent()) {
            long micros = lengthOpt.get();
            Duration duration = Duration.ofNanos(micros * 1000);
            return Optional.of(duration);
        }

        // Try alternative duration metadata keys that some implementations might use
        Optional<Long> altLengthOpt = getLongValue("xesam:length");
        if (altLengthOpt.isPresent()) {
            long micros = altLengthOpt.get();
            Duration duration = Duration.ofNanos(micros * 1000);
            return Optional.of(duration);
        }

        return Optional.empty();
    }

    @Override
    public Optional<Duration> getPosition() {
        Optional<Long> positionMicros = readPositionMicros();
        if (positionMicros.isPresent()) {
            return Optional.of(Duration.ofNanos(positionMicros.get() * 1000));
        }
        return Optional.empty();
    }

    private Optional<Long> readPositionMicros() {
        if (properties != null) {
            try {
                Object positionObj = properties.Get("org.mpris.MediaPlayer2.Player", "Position");
                Object unwrapped = MprisMetadataUtils.unwrap(positionObj);
                Optional<Long> coerced = coerceToLong(unwrapped);
                if (coerced.isPresent()) {
                    return coerced;
                }
            } catch (Exception ignored) {
                // Fallback to direct method
            }
        }

        if (player != null) {
            try {
                return Optional.of(player.getPosition());
            } catch (Exception ignored) {
                // Position might not be available via direct call
            }
        }

        return Optional.empty();
    }

    private Optional<Long> coerceToLong(Object value) {
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof String stringValue) {
            try {
                return Optional.of(Long.parseLong(stringValue));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            for (Object element : list) {
                Optional<Long> coerced = coerceToLong(element);
                if (coerced.isPresent()) {
                    return coerced;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Map<String, String> getAdditionalMetadata() {
        Map<String, String> additional = new HashMap<>();

        getStringValue("xesam:genre").ifPresent(genre -> additional.put("genre", genre));
        getStringValue("xesam:url").ifPresent(url -> additional.put("url", url));
        getLongValue("xesam:trackNumber").ifPresent(trackNum -> additional.put("trackNumber", trackNum.toString()));

        return additional;
    }

    @Override
    public boolean isLiveStream() {
        return getDuration().map(Duration::isZero).orElse(false);
    }

    @Override
    public Instant getLastUpdated() {
        return lastUpdated;
    }

    private Optional<String> getStringValue(String key) {
        Object value = metadata.get(key);
        return coerceToString(value);
    }

    private Optional<List<String>> getStringList(String key) {
        Object value = metadata.get(key);
        if (value instanceof List<?> list && !list.isEmpty()) {
            List<String> strings = new ArrayList<>();
            for (Object element : list) {
                coerceToString(element).ifPresent(strings::add);
            }
            if (!strings.isEmpty()) {
                return Optional.of(strings);
            }
        } else {
            Optional<String> coerce = coerceToString(value);
            if (coerce.isPresent()) {
                return Optional.of(List.of(coerce.get()));
            }
        }
        return Optional.empty();
    }

    private Optional<Long> getLongValue(String key) {
        Object value = metadata.get(key);
        if (value instanceof Number number) {
            return Optional.of(number.longValue());
        }
        if (value instanceof String str) {
            try {
                return Optional.of(Long.parseLong(str));
            } catch (NumberFormatException ignored) {
                // ignore
            }
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            for (Object element : list) {
                Optional<Long> coerced = coerceToLong(element);
                if (coerced.isPresent()) {
                    return coerced;
                }
            }
        }
        return Optional.empty();
    }

    private Optional<String> coerceToString(Object value) {
        if (value instanceof CharSequence sequence && sequence.length() > 0) {
            return Optional.of(sequence.toString());
        }
        if (value instanceof byte[] bytes) {
            String decoded = new String(bytes, StandardCharsets.UTF_8).trim();
            if (!decoded.isEmpty()) {
                return Optional.of(decoded);
            }
        }
        if (value instanceof List<?> list) {
            for (Object element : list) {
                Optional<String> coerced = coerceToString(element);
                if (coerced.isPresent()) {
                    return coerced;
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof LinuxNowPlaying)) return false;
        LinuxNowPlaying other = (LinuxNowPlaying) obj;

        return Objects.equals(getTitle(), other.getTitle()) &&
                Objects.equals(getArtist(), other.getArtist()) &&
                Objects.equals(getAlbum(), other.getAlbum());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getTitle(), getArtist(), getAlbum());
    }
}

