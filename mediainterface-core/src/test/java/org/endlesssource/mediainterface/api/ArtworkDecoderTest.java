package org.endlesssource.mediainterface.api;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtworkDecoderTest {

    @Test
    void decodesPlainBase64() {
        byte[] input = "hello-art".getBytes(StandardCharsets.UTF_8);
        String base64 = Base64.getEncoder().encodeToString(input);
        assertTrue(ArtworkDecoder.decodeBytes(base64).isPresent());
        assertArrayEquals(input, ArtworkDecoder.decodeBytes(base64).orElseThrow());
    }

    @Test
    void decodesDataUriBase64() {
        byte[] input = "img-bytes".getBytes(StandardCharsets.UTF_8);
        String dataUri = "data:image/png;base64," + Base64.getEncoder().encodeToString(input);
        assertTrue(ArtworkDecoder.decodeBytes(dataUri).isPresent());
        assertArrayEquals(input, ArtworkDecoder.decodeBytes(dataUri).orElseThrow());
    }

    @Test
    void decodesLocalFilePath() throws Exception {
        byte[] input = "file-bytes".getBytes(StandardCharsets.UTF_8);
        Path temp = Files.createTempFile("artwork-decoder-test", ".bin");
        Files.write(temp, input);
        temp.toFile().deleteOnExit();

        assertTrue(ArtworkDecoder.decodeBytes(temp.toString()).isPresent());
        assertArrayEquals(input, ArtworkDecoder.decodeBytes(temp.toString()).orElseThrow());
    }
}

