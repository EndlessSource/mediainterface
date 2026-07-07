package org.endlesssource.mediainterface.linux;

import org.endlesssource.mediainterface.api.NowPlaying;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable view over an MPRIS metadata map. Holds no D-Bus references and
 * performs no I/O; the playback position is supplied by the owning session.
 */
class LinuxNowPlaying implements NowPlaying {
    private final Map<String, Object> metadata;
    private final Instant lastUpdated;

    LinuxNowPlaying(Map<String, Object> metadata) {
        this.metadata = new HashMap<>(metadata);
        this.lastUpdated = Instant.now();
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
        if (lengthOpt.isEmpty()) {
            // Alternative duration metadata key that some implementations use
            lengthOpt = getLongValue("xesam:length");
        }
        return lengthOpt.map(micros -> Duration.ofNanos(micros * 1000));
    }

    @Override
    public Optional<Duration> getPosition() {
        return Optional.empty();
    }

    Optional<org.freedesktop.dbus.ObjectPath> getTrackId() {
        Object value = MprisMetadataUtils.unwrap(metadata.get("mpris:trackid"));
        if (value instanceof org.freedesktop.dbus.ObjectPath) {
            return Optional.of((org.freedesktop.dbus.ObjectPath) value);
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
        return coerceToString(metadata.get(key));
    }

    private Optional<List<String>> getStringList(String key) {
        Object value = metadata.get(key);
        if (value instanceof List<?> && !((List<?>) value).isEmpty()) {
            List<String> strings = new ArrayList<>();
            for (Object element : (List<?>) value) {
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
        return coerceToLong(metadata.get(key));
    }

    private static Optional<Long> coerceToLong(Object value) {
        if (value instanceof Number) {
            return Optional.of(((Number) value).longValue());
        }
        if (value instanceof String) {
            try {
                return Optional.of(Long.parseLong((String) value));
            } catch (NumberFormatException ignored) {
                return Optional.empty();
            }
        }
        if (value instanceof List<?>) {
            for (Object element : (List<?>) value) {
                Optional<Long> coerced = coerceToLong(element);
                if (coerced.isPresent()) {
                    return coerced;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<String> coerceToString(Object value) {
        if (value instanceof CharSequence && ((CharSequence) value).length() > 0) {
            return Optional.of(value.toString());
        }
        if (value instanceof byte[]) {
            String decoded = new String((byte[]) value, StandardCharsets.UTF_8).trim();
            if (!decoded.isEmpty()) {
                return Optional.of(decoded);
            }
        }
        if (value instanceof List<?>) {
            for (Object element : (List<?>) value) {
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
