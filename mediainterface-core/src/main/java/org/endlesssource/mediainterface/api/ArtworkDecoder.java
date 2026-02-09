package org.endlesssource.mediainterface.api;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Optional;

/**
 * Utility for decoding artwork payloads returned by {@link NowPlaying#getArtwork()}.
 */
public final class ArtworkDecoder {

    private ArtworkDecoder() {
    }

    /**
     * Decodes artwork from data URI, plain base64, file path, or file/http URL.
     *
     * @param artworkValue artwork field value
     * @return decoded bytes if successful
     */
    public static Optional<byte[]> decodeBytes(String artworkValue) {
        if (artworkValue == null || artworkValue.isBlank()) {
            return Optional.empty();
        }

        String value = artworkValue.trim();
        String base64 = value;
        int comma = value.indexOf(',');
        if (value.startsWith("data:") && comma >= 0) {
            base64 = value.substring(comma + 1);
            try {
                byte[] bytes = Base64.getDecoder().decode(base64);
                return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
            } catch (IllegalArgumentException ex) {
                return Optional.empty();
            }
        }

        if (value.startsWith("file:") || value.startsWith("http://") || value.startsWith("https://")) {
            try {
                URL url = URI.create(value).toURL();
                return Optional.of(url.openStream().readAllBytes());
            } catch (IllegalArgumentException | IOException ex) {
                return Optional.empty();
            }
        }

        try {
            Path path = Path.of(value);
            if (Files.exists(path)) {
                return Optional.of(Files.readAllBytes(path));
            }
        } catch (IllegalArgumentException | IOException ignored) {
            // fall through to plain base64 decode
        }

        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            return bytes.length == 0 ? Optional.empty() : Optional.of(bytes);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
